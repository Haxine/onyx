(ns onyx.log.generators
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.log.commands.common :as common :refer [peer->allocated-job]]
            [onyx.log.commands.leave-cluster :as lc]
            [onyx.log.commands.group-leave-cluster :as glc]
            [onyx.extensions :as extensions]
            [onyx.messaging.protocols.messenger :as m]
            [onyx.api :as api]
            [taoensso.timbre :as timbre :refer [info]]
            [clojure.set :refer [intersection]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer :all]))

(def peer-config 
  {:onyx.messaging/impl :aeron
   :onyx.peer/try-join-once? false})

(def messenger-group (m/build-messenger-group peer-config))
(def messenger (m/build-messenger peer-config messenger-group {} :FAKE-ID nil))

(defn one-group [replica]
  (-> replica
      (assoc :groups [:g1])
      (assoc-in [:groups-index :g1] (into #{} (:peers replica)))
      ((fn [rep]
         (reduce
          #(assoc-in %1 [:groups-reverse-index %2] :g1)
          rep
          (:peers rep))))))

(defn peerless-entry? [log-entry]
  (#{:submit-job :kill-job :gc} (:fn log-entry)))

(defn active-groups [replica entry]
  (cond-> (set (concat (:groups replica)
                       ;; might not need these with the below entries
                       (vals (or (:prepared replica) {}))
                       (vals (or (:accepted replica) {}))))
    ;; joining peer's prepared/accepted may have been removed
    ;; by a leave cluster but the joining peer is still there
    (get #{:prepare-join-cluster :accept-join-cluster 
           :abort-join-cluster :notify-join-cluster :group-leave-cluster} (:fn entry))
    (into (remove nil? ((juxt :observer :id :accepted-observer 
                              :accepted-joiner :joiner) 
                        (:args entry))))))

(defn active-peers [replica entry]
  (:peers replica))

(defn generate-side-effects
  "Generates additional reactions that may be generated by the process.
  e.g. when the task lifecycle is ready, it signals via :signal-ready"
  [entry old new diff state]
  nil
  ; (cond (and (= :peer (:type state)) 
  ;            (#{:kill-job :submit-job :add-virtual-peer
  ;               :prepare-join-cluster :accept-join-cluster :notify-join-cluster
  ;               :leave-cluster :group-leave-cluster :seal-output} (:fn entry)))
  ;   (let [peer-id (:id state)
  ;         old-allocation (peer->allocated-job (:allocations old) peer-id)
  ;         new-allocation (peer->allocated-job (:allocations new) peer-id)]
  ;     (if (and new-allocation (not= old-allocation new-allocation))
  ;       [peer-id [(create-log-entry :signal-ready {:id peer-id})]])))
  )

(defn iterate-reactions [entry old-replica new-replica diff state]
  (let [reactions (extensions/reactions entry old-replica new-replica diff state)]
    (when (seq reactions)
      [(:id state) reactions])))

(defn collect-reactions [entry old-replica new-replica diff actors]
  (keep (partial iterate-reactions entry old-replica new-replica diff) actors))

(defn collect-side-effects [entry old-replica new-replica diff actors]
  (keep (partial generate-side-effects entry old-replica new-replica diff) actors))

(defn new-state [type id]
  {:messenger messenger
   :type type
   :id id
   :opts {:onyx.peer/try-join-once?
          (:onyx.peer/try-join-once? (:opts messenger) true)}})

(defn same-slot-id? [old new job-id task-id peer-id]
  (= (get-in old [:task-slot-ids job-id task-id peer-id])
     (get-in new [:task-slot-ids job-id task-id peer-id])))

(defn n-expected-reallocations [old-allocations left-allocations new-allocations]
  (reduce
   (fn [sum job-id]
     (reduce
      (fn [sum* task-id]
        (+ sum*
           (- (count (get-in old-allocations [job-id task-id] []))
              (count (get-in left-allocations [job-id task-id] [])))
           (Math/abs
            (- (count (get-in left-allocations [job-id task-id] []))
               (count (get-in new-allocations [job-id task-id] []))))))
      sum
      (keys (get-in old-allocations [job-id] {}))))
   0
   (keys old-allocations)))

(defn scheduler-invariants
  "This assertion checks that the minimum number of peers
   move between tasks when the scheduler makes a change.
   It's based on an algorithm that uses the planned capacity
   limits of each task to figure out how many peers joined
   and left their respective tasks, and compares this to
   the total number of position changes in the tasks. We
   assert that the number of actual position changes is
   lower than or equal to the number that we calculate
   based on the capacity limits.

   The maximum expected number of reallocations is determined
   by creating a matrix of N rows, where N is the number
   of tasks. The order of the rows doesn't matter. This matrix
   has 3 columns. The first column represents the number of
   peers assigned to this task in the old replica. The last
   column represents the number of peers assigned to this task
   in the new replica. The middle column represents the number
   of peers assigned to this task *after* any peers that are
   leaving as a result of the application of this log entry
   have been removed from the replica. Thus, we have a Nx3 matrix.

   For each row in the matrix, the second column is substracted
   from the first column. This number yields the number of peer
   removals for this task in an exact manner. Then, this number
   is added to the absolute value of subtracting the third column
   from the second column. This value yields the number of peer
   additions, if any. We take the absolute value since the scheduler
   can decide to deallocate this task and drop it to zero.

   All the values for each row are summed, and this determines the
   expected number of peer position changes. This is effective because
   it doesn't conflate the tentative schedules at deallocate and
   reallocation time.

   We'll look at two examples.

   1. Three tasks (1/2/1 allocations), 1 peer leaves

   Initial matrix:
   ---------------
   [ 1  0  1 ]
   [ 2  2  1 ]
   [ 1  1  1 ]

   Calculated deltas (ignoring 0's):
   ---------------------------------
   [ 1 <-- +1 --> 0 <-- +1 --> 1 ]
   [ 2            2 <-- +1 --> 1 ]
   [ 1            1            1 ]

   So this allocation ends up as (1/1/1). Summing each
   of the deltas (1 + 1 + 1), we end up with an expected
   number of 3 for the reallocations value.

   2. Three tasks (3/5/1 allocations), 2 peers (a group) leave
      the first task, where the first task must maintain 3 peers
      at a minimum.

   Initial matrix:
   ---------------
   [ 3  1  3 ]
   [ 5  5  3 ]
   [ 1  1  1 ]

   Calculated deltas (ignoring 0's):
   ---------------------------------
   [ 3 <-- +2 --> 1 <-- +2 --> 3 ]
   [ 5            5 <-- +2 --> 3 ]
   [ 1            1            1 ]

   The allocations end up at (3/3/1), satisfying the minimum
   number of peers for the first task. Adding the deltas together,
   (2 + 2 + 2), we get an expected reallocation value of 6."
  [old-replica new-replica entry]
  (let [prev-allocation (set (common/allocations->peers (:allocations old-replica)))
        new-allocation (set (common/allocations->peers (:allocations new-replica)))
        deallocated (clojure.set/difference prev-allocation new-allocation)
        new-allocated (clojure.set/difference new-allocation prev-allocation)
        same-allocated (clojure.set/intersection new-allocation prev-allocation)
        prev-allocated-peers (set (map first prev-allocation))
        new-allocated-peers (set (map first new-allocation))
        prev-unallocated (clojure.set/difference (set (:peers old-replica)) prev-allocated-peers)
        allocated-peers-left (clojure.set/difference prev-allocated-peers (set (:peers new-replica)))
        newly-joined-peers (clojure.set/difference (set (:peers new-replica))  (set (:peers old-replica)))
        n-reallocations (+ (count new-allocated)
                           (count deallocated))
        n-expected-reallocs
        (n-expected-reallocations
         (:allocations old-replica)
         (cond (= (:fn entry) :leave-cluster)
               (:allocations (lc/deallocated-replica (:args entry) old-replica))
               (= (:fn entry) :group-leave-cluster)
               (:allocations (glc/deallocated-replica (:args entry) old-replica))
               :else
               (:allocations old-replica))
         (:allocations new-replica))]
    (assert (empty?
             (remove (fn [[peer {:keys [job task]}]]
                       (same-slot-id? old-replica new-replica job task peer))
                     same-allocated))
            "No slot-id churn allowed on peers allocated to the same task.")

    (assert (or
             ;; If different jobs are allocated between replicas
             ;; Then the lower bound is incorrect
             (not= (set (keys (:allocations old-replica)))
                   (set (keys (:allocations new-replica))))

             (not= (set (:jobs old-replica))
                   (set (:jobs new-replica)))

             ;; All bets are off about churn when using tags since
             ;; we can't use our typical jitter constraints in BtrPlace.
             (some (set (:jobs new-replica))
                   (set (keys (:required-tags new-replica))))

             (<= n-reallocations n-expected-reallocs))
            (pr-str (format "Potentionally bad reallocations. Expected %s, actually performed %s"
                            n-expected-reallocs n-reallocations)
                    old-replica
                    new-replica
                    entry))))

(defn apply-entry [old-replica entries entry]
  (let [new-replica (extensions/apply-log-entry entry (assoc old-replica :version (:message-id entry)))
        _ (scheduler-invariants old-replica new-replica entry)
        diff (extensions/replica-diff entry old-replica new-replica)
        actor-states (into (mapv #(new-state :group %) (active-groups new-replica entry)) 
                           (map #(new-state :peer %) (active-peers new-replica entry)))
        actor-reactions (collect-reactions entry old-replica new-replica diff actor-states)
        side-effects (collect-side-effects entry old-replica new-replica diff actor-states)
        new (concat actor-reactions side-effects)
        ; it does not matter that multiple reactions are processed
        ; together because they may be processed interleaved depending on
        ; the choice of peer queue being popped
        unapplied (reduce (fn [new-entries [actor-id reactions]]
                            (update-in 
                             new-entries
                             [actor-id :queue]
                             (fn [queue]
                               (into (vec queue) reactions))))
                          entries
                          new)]
    (vector new-replica diff unapplied {:actors (map :id actor-states)
                                        :reactions new})))

(defn apply-peer-queue-entry
  "Applies the next log message in the selected peer's queue.
  Effectively, the next peer that wrote its message to ZK"
  [{:keys [replica message-id entries peer-choices log]} next-group]
  (let [peer-queue (:queue (entries next-group))
        next-entry (first peer-queue)
        new-peer-queue (vec (rest peer-queue))
        new-entries (if (empty? new-peer-queue)
                      (dissoc entries next-group)
                      (assoc-in entries [next-group :queue] new-peer-queue))
        message (assoc next-entry :message-id message-id)
        [new-replica diff updated-entries reactions] (apply-entry replica new-entries message)]
    {:replica new-replica
     :message-id (inc message-id)
     :entries updated-entries
     :log (conj log [message diff reactions])
     :peer-choices (conj peer-choices next-group)}))

(defn queue-select-gen
  "Generator to look into all of the peer's write queues
  and pick an entry to get fake written next"
  [replica-state-gen]
  (gen/bind replica-state-gen
            (fn [state]
              ;; we only play back log messages from peers who have joined
              (let [replica (:replica state)
                    peerless-queues (->> (:entries state)
                                         (filter (fn [[queue-id {:keys [predicate queue]}]]
                                                   (or (peerless-entry? (first queue))
                                                       ((or predicate (constantly true))
                                                        replica
                                                        (first queue)))))
                                         (map key))
                    joined-peers (set (:peers replica))
                    joined-groups (set (:groups replica))
                    selectable-peers (->> (:entries state)
                                          (filter (fn [[peer {:keys [queue]}]]
                                                    (let [entry (first queue)]
                                                      (contains? joined-peers peer))))
                                          (map key)
                                          set)
                    selectable-groups (->> (:entries state)
                                           (filter (fn [[group {:keys [queue]}]]
                                                     (let [entry (first queue)]
                                                       (contains? joined-groups group))))
                                           (map key)
                                           set)
                    selectable-queues (into selectable-groups (into selectable-peers peerless-queues))]
                (if (empty? selectable-queues)
                  (throw (Exception. (str "No playable log messages. State: " state)))
                  (gen/elements selectable-queues))))))

(defn apply-entry-gen
  "Apply an entry from one of the peers log queues
  to a replica generator "
  [replica-state-gen]
  (gen/fmap
    (fn [[state peer-id]]
      (apply-peer-queue-entry state peer-id))
    (gen/tuple replica-state-gen
               (queue-select-gen replica-state-gen))))

(defn apply-entries-gen
  "Recurse over replica generator until entries
  are exhausted. Return the final replica, the log messages
  in the order they were written and the order of the peers
  that got to write"
  [replica-state-gen]
  (gen/bind replica-state-gen
            (fn [state]
              (let [g (gen/return state)]
                (when (> (count (:log state))
                         1000)
                  (throw (Exception. (str "Log entry generator overflow. Likely issue with uncompletable log\n"
                                          (with-out-str (clojure.pprint/pprint state))))))
                (if (empty? (:entries state))
                  g
                  (apply-entries-gen (apply-entry-gen g)))))))

(defn build-add-vpeer-entry [entries group-id peer-ids more-args]
  (reduce
   (fn [result peer-id]
     (let [queue-key (keyword (str (name peer-id) "-join"))] 
       (assert (not (get result queue-key)))
       (assoc
        result
        queue-key
        {:queue
         [{:fn :add-virtual-peer
           :args (merge
                  {:id peer-id
                   :group-id group-id
                   ; FIXME
                   ;:peer-site (m/get-peer-site nil nil)
                   }
                  more-args)}]})))
   entries
   peer-ids))

(defn generate-join-queues
  ([group-and-peer-ids]
   (generate-join-queues group-and-peer-ids {}))
  ([group-and-peer-ids more-join-args]
   (reduce-kv
    (fn [result group-id peer-ids]
      (-> result
          (assoc group-id
                 {:queue
                  [{:fn :prepare-join-cluster
                    :args (merge {:joiner group-id} more-join-args)}]})
          (build-add-vpeer-entry group-id peer-ids more-join-args)))
    {}
    group-and-peer-ids)))

(defn generate-group-and-peer-ids
  ([groups peers]
   (generate-group-and-peer-ids 1 groups
                                1 peers))
  ([groups-low groups-high peers-low peers-high]
   (reduce
    (fn [r g]
      (let [g-id (keyword (str "g" g))
            peers (map
                   #(keyword (str (name g-id) "-p" %))
                   (range peers-low (+ peers-low peers-high)))]
        (assoc r g-id (into #{} peers))))
    {}
    (range groups-low (+ groups-low groups-high)))))
