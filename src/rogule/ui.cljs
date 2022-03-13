(ns rogule.ui
  (:require
    [clojure.set :refer [intersection]]
    [clojure.string :refer [join]]
    [reagent.core :as r]
    [reagent.dom :as rdom]
    [sitefox.ui :refer [log]]
    [rogule.emoji :refer [tile-mem]]
    [rogule.map :refer [make-digger-map distance-sq room-center tiles-for-room find-path]]
    [rogule.engine :refer [install-arrow-key-handler move-to add-to-combat-list
                           add-message finish-game add-item-to-inventory uncover-item]]
    ["rot-js" :as ROT]
    ["seedrandom" :as seedrandom])
  (:require-macros
    [rogule.loader :refer [load-sprite]]))

(def initial-state
  {:message {:expires 5
             :text "Press ? for help."}})

(defonce state (r/atom initial-state))
(defonce combat-dice (ROT/RNG.clone))

(def size 32)
(def visible-dist 9)
(def visible-dist-sq (js/Math.pow visible-dist 2))
(def clear-dist 7)
(def clear-dist-sq (js/Math.pow clear-dist 2))
(def coin #js [0 1])

#_ (def forage-items
  [{:name "herbs"
    :sprite (load-sprite :herb)
    :value 1}
   {:name "feather"
    :sprite (load-sprite :feather)
    :value 1}
   {:name "bone"
    :sprite (load-sprite :bone)
    :value 1}
   {:name "olive sprig"
    :sprite (load-sprite :olive)
    :value 1}

   {:name "egg"
    :sprite (load-sprite :egg)
    :value 2}
   {:name "grapes"
    :sprite (load-sprite :grapes)
    :value 2}
   {:name "shell"
    :sprite (load-sprite :spiral-shell)
    :value 2}

   {:name "mushroom"
    :sprite (load-sprite :mushroom)
    :value 4}
   {:name "chestnut"
    :sprite (load-sprite :chestnut)
    :value 4}

   {:name "gem"
    :sprite (load-sprite :gem-stone)
    :value 8}])

(def forage-items
  [{:name "chestnut"
    :sprite (load-sprite :chestnut)
    :fns {:encounter #'add-item-to-inventory}
    :value 1}
   {:name "mushroom"
    :sprite (load-sprite :mushroom)
    :fns {:encounter #'add-item-to-inventory}
    :value 2}
   {:name "egg"
    :sprite (load-sprite :egg)
    :fns {:encounter #'add-item-to-inventory}
    :value 2}])

(def item-covers
  [{:sprite (load-sprite :hole)
    :fns {:encounter #'uncover-item}
    :name "hole"}
   {:sprite (load-sprite :rock)
    :fns {:encounter #'uncover-item}
    :name "rock"}
   {:sprite (load-sprite :wood)
    :fns {:encounter #'uncover-item}
    :name "wood block"}])

(def indoor-scenery
  [{:sprite (load-sprite :fountain)
    :name "fountain"}
   {:sprite (load-sprite :potted-plant)
    :name "pot plant"}
   {:sprite (load-sprite :moai)
    :name "statue"}])

(def shrine-template {:sprite (load-sprite :shinto-shrine)
                      :name "shrine"})

(def monster-table
  [{:sprite (load-sprite :rat)
    :activation 3
    :stats {:xp 1 :hp [2 2]}
    :name "the rat"}
   {:sprite (load-sprite :bat)
    :activation 10
    :stats {:xp 1 :hp [3 3]}
    :name "the bat"}

   {:sprite (load-sprite :ghost)
    :activation 10
    :stats {:xp 2 :hp [3 3]}
    :name "the ghost"}
   {:sprite (load-sprite :boar)
    :activation 15
    :stats {:xp 2 :hp [4 4]}
    :name "the boar"}
   {:sprite (load-sprite :wolf)
    :activation 20
    :stats {:xp 2 :hp [5 5]}
    :name "the wolf"}
   {:sprite (load-sprite :ogre)
    :activation 10
    :stats {:xp 2 :hp [7 7]}
    :name "the ogre"}

   {:sprite (load-sprite :vampire)
    :activation 15
    :stats {:xp 3 :hp [8 8]}
    :name "the vampire"}
   {:sprite (load-sprite :zombie)
    :activation 5
    :stats {:xp 3 :hp [9 9]}
    :name "the zombie"}
   {:sprite (load-sprite :genie)
    :activation 20
    :stats {:xp 3 :hp [10 10]}
    :name "the genie"}

   {:sprite (load-sprite :dragon)
    :activation 10
    :stats {:xp 4 :hp [15 15]}
    :name "the dragon"}
   {:sprite (load-sprite :t-rex)
    :activation 15
    :stats {:xp 4 :hp [12 12]}
    :name "the t-rex"}])

; ***** utility functions ***** ;

(defn entities-by-pos [entities]
  (reduce (fn [es [id e]] (assoc es (conj (:pos e) (:layer e)) (assoc e :id id))) {} entities))

(def entities-by-pos-mem (memoize entities-by-pos))

(defn date-token []
  (let [today (js/Date.)]
    (str (.getFullYear today) "-"
         (inc (.getMonth today)) "-"
         (.getDate today))))

(defn make-id []
  (-> (random-uuid) str (.slice 0 8)))

(defn coin-flip []
  (.getItem combat-dice coin))

(defn get-random-entity-by-value [entity-template-table]
  (let [weighted-table (->> entity-template-table
                            (map (fn [i] {(:name i) (/ 1 (:value i))}))
                            (into {})
                            clj->js)
        item-name (ROT/RNG.getWeightedValue weighted-table)]
    (->> entity-template-table
         (filter #(= (:name %) item-name))
         first)))

(defn calculate-max-score [entities]
  (reduce (fn [score [_id e]]
            (let [value (some identity [(:value e) (-> e :drop :value) 0])]
              (+ score value)))
          0 entities))

; ***** item interaction functions ***** ;

(defn check-for-endgame [*state]
  (let [player (-> *state :entities :player)]
    (if (:dead player)
      (assoc *state :outcome :died)
      *state)))

(defn combat [*state their-id my-id]
  ; hit goes them -> me
  (let [them (get-in *state [:entities their-id])
        me (get-in *state [:entities my-id])
        their-xp (-> them :stats :xp)
        my-hp (-> me :stats :hp first)
        ; flip a coin for every xp and compute the boolean
        hits (map (fn [_] (coin-flip)) (range their-xp))
        hp-reduction (-> hits join (js/parseInt 2))
        updated-hp (js/Math.max 0 (- my-hp hp-reduction))
        hit-miss-msg (if (= hp-reduction 0) "missed" "hit")
        killed (= updated-hp 0)
        *state (assoc-in *state [:entities my-id :stats :hp 0] updated-hp)
        *state (add-message *state (str (:name them) " " hit-miss-msg " " (:name me)))
        *state (if killed
                 (add-message *state (str (:name them) " killed " (:name me)))
                 (-> *state
                     (add-to-combat-list their-id (get-in *state [:entities their-id]))
                     (add-to-combat-list my-id (get-in *state [:entities my-id]))))]
    (log "combat" (:name them) "hit" (:name me) hits hp-reduction " hp:" my-hp updated-hp)
    [true
     (if (= updated-hp 0)
       (-> *state ; entity dies
           (update-in [:entities my-id] assoc
                      :dead true
                      :layer :floor
                      :sprite (load-sprite :skull-and-crossbones))
           (update-in [:entities my-id :fns] dissoc :update :encounter)
           (check-for-endgame))
       *state)]))

(defn can-pass-tile [floor-tiles pos allowed-tiles]
  (let [tile-type (get floor-tiles pos)]
       (contains? (set allowed-tiles) tile-type)))

(defn player-passable-fn [*state x y]
  (let [floor-tiles (-> *state :map :floor-tiles)]
    (can-pass-tile floor-tiles [x y] [:room :door :corridor])))

(defn make-monster-passable-fn [*state monster-id _monster]
  (let [floor-tiles (-> *state :map :floor-tiles)
        entities (-> *state :entities)
        entities-to-avoid (->>
                            entities
                            (filter (fn [[id e]] (and
                                                   (= (:layer e) :occupy)
                                                   (not= id monster-id)
                                                   (not= id :player))))
                            entities-by-pos-mem)]
    (fn [x y]
      (and
        (can-pass-tile floor-tiles [x y] [:room :door :corridor])
        (nil? (get entities-to-avoid [x y :occupy]))))))

(defn chase-player [{:keys [entities] :as *state} monster-id monster]
  (log "update" (:name monster) monster-id)
  (let [player (:player entities)
        passable-fn ((-> monster :fns :passable) *state monster-id monster)
        path-to-player (when player
                         (find-path
                           (:pos monster) (:pos player)
                           passable-fn))]
    (if (and player
             (< (count path-to-player) (:activation monster))
             ; every now and then just don't move
             (< (.getUniform combat-dice) 0.9))
      (move-to *state monster-id (second path-to-player))
      *state)))

; ***** create different types of things ***** ;

(defn make-player [entities free-tiles]
  (let [pos (rand-nth (keys free-tiles))
        player {:sprite (load-sprite :elf)
                :name "you"
                :layer :occupy
                :pos pos
                :stats {:hp [10 10]
                        :xp 2}
                :inventory []
                :fns {:encounter #'combat
                      :passable (fn [*state _player-id _player]
                                  (partial player-passable-fn *state))}}]
    [(assoc entities :player player)
     (dissoc free-tiles pos)]))

(defn make-shrine [entities free-tiles paths-to-rooms]
  (let [pos (:center-pos (last paths-to-rooms))
        shrine (merge shrine-template
                      {:pos pos
                       :layer :occupy
                       :fns {:encounter #'finish-game}})]
    [(assoc entities :shrine shrine)
     (dissoc free-tiles pos)]))

(defn pos-to-difficulty [player pos paths-to-rooms player-path-find-fn]
  (let [furthest-room-path-length (count (:path (last paths-to-rooms)))
        path-to-item (find-path
                       (:pos player) pos
                       player-path-find-fn)
        path-to-item-length (count path-to-item)]
    (/ path-to-item-length furthest-room-path-length)))

(defn make-covered-item [entities free-tiles paths-to-rooms player-path-find-fn]
  (let [{:keys [room]} (rand-nth paths-to-rooms)
        room-tiles (tiles-for-room room)
        free-room-tiles (intersection (set (keys room-tiles)) (set (keys free-tiles)))
        pos (rand-nth (vec free-room-tiles))
        difficulty (-> (pos-to-difficulty (:player entities) pos paths-to-rooms player-path-find-fn)
                       (* 0.9))
        item-template (get-random-entity-by-value forage-items)
        item (when
               (> (js/Math.random) difficulty)
               (merge
                 item-template
                 {:pos pos
                  :id (make-id)
                  :layer :floor}))
        cover (merge
                (rand-nth item-covers)
                {:pos pos
                 :layer :floor
                 :drop item})]
    [(assoc entities (make-id) cover)
     (dissoc free-tiles pos)]))

(defn make-monster [entities free-tiles paths-to-rooms player-path-find-fn]
  (let [pos (rand-nth (keys free-tiles))
        difficulty (-> (pos-to-difficulty (:player entities) pos paths-to-rooms player-path-find-fn)
                       (* 0.75)
                       (js/Math.min 1))
        min-index 0
        max-index (dec (count monster-table))
        monster-difficulty-index (js/Math.floor (* difficulty max-index))
        monster-sub-table {monster-difficulty-index 6
                           (js/Math.min (+ monster-difficulty-index 1) max-index) 2
                           (js/Math.max (- monster-difficulty-index 1) min-index) 2
                           (js/Math.min (+ monster-difficulty-index 2) max-index) 1
                           (js/Math.max (- monster-difficulty-index 2) min-index) 1}
        monster-index (js/parseInt (ROT/RNG.getWeightedValue (clj->js monster-sub-table)))
        monster (merge
                  (nth monster-table monster-index)
                  {:pos pos
                   :layer :occupy
                   :fns {:encounter #'combat
                         :update #'chase-player
                         :passable #'make-monster-passable-fn}})]
    [(assoc entities (make-id) monster)
     (dissoc free-tiles pos)]))

(defn make-entities [game-map entity-count monster-count]
  (let [tiles (:tiles game-map)
        free-tiles (merge
                     (:room tiles)
                     (:corridor tiles))
        [entities free-tiles] (make-player {} free-tiles)
        player (:player entities)
        floor-tiles (:floor-tiles game-map)
        player-path-find-fn (fn [x y] (can-pass-tile floor-tiles [x y] [:room :door :corridor]))
        paths-to-rooms (->> (:rooms game-map)
                            (map (fn [room] [room (room-center room)]))
                            (map (fn [[room room-center-pos]]
                                   (let [path (find-path
                                                (:pos player) room-center-pos
                                                player-path-find-fn)]
                                     {:center-pos room-center-pos
                                      :room room
                                      :path path})))
                            (sort-by (juxt :path count)))
        [entities free-tiles] (make-shrine entities free-tiles paths-to-rooms)
        [entities free-tiles] (reduce
                                (fn [[entities free-tiles] _i]
                                  (make-covered-item entities free-tiles paths-to-rooms player-path-find-fn))
                                [entities free-tiles]
                                (range entity-count))
        [entities] (reduce
                     (fn [[entities free-tiles] _i]
                       (make-monster entities free-tiles paths-to-rooms player-path-find-fn))
                     [entities free-tiles]
                     (range monster-count))]
    entities))

(defn make-level [*state size]
  (let [m (make-digger-map (js/Math.random) size size)
        entities (make-entities m 20 5)
        max-score (calculate-max-score entities)]
    (log "map" m)
    (log "entities" entities)
    (log "max-score" max-score)
    (assoc *state
           :map m
           :entities entities
           :max-score max-score)))

; ***** rendering ***** ;

(defn component-cell [floor-tiles entities x y opacity]
  [:span.grid {:key x
               :style {:opacity opacity}}
   (when (> opacity 0)
     (cond
       (= (get floor-tiles [x y]) :door)
       (tile-mem (load-sprite :white-large-square) "door")
       (= (get floor-tiles [x y]) :wall)
       (tile-mem (load-sprite :black-large-square) "wall")
       (= (get floor-tiles [x y]) :corridor)
       (tile-mem (load-sprite :brown-square) "corridor")
       :else nil))
   (for [layer [:floor :occupy]]
     (let [entity (get entities [x y layer])]
       (when entity
         [:span
          (tile-mem (:sprite entity) (:name entity) {:opacity opacity})
          (when (and (:stats entity) (not= (:id entity) :player))
            [:span.stat (-> entity :stats :xp)])])))])

(defn component-inventory [inventory]
  [:div#inventory
   [:div#score (apply + (map :value inventory))]
   [:ul
    (for [e (sort-by (juxt :value :name) inventory)]
      [:li (tile-mem (:sprite e) (:name e) {:width "48px"})])]])

(defn component-health-bar [entity stats]
  [:div
   (tile-mem (:sprite entity))
   [:span.xp (-> entity :stats :xp)]
   (let [hp (-> stats :hp first)]
     (for [i (range (-> stats :hp second))]
       (if (> i hp)
         (tile-mem (load-sprite :white-large-square))
         (tile-mem (load-sprite :green-square)))))])

(defn component-health-bars [player combatants]
  [:div#health-bars
   [component-health-bar player (:stats player)]
   (for [[_id entity] combatants]
     (let [stats (:stats entity)] [component-health-bar entity stats]))])

(def component-health-bars-mem (memoize component-health-bars))

(defn component-help [show-help]
  (when show-help
    [:div.modal
     [:h2 "Rogule"]
     [:p "Find items to obtain the best score."]
     [:p "Get to the shrine " (tile-mem (load-sprite :shinto-shrine) "shrine") " to ascend."]]))

(defn component-messages [message]
  [:div.message message])

(defn component-game [state]
  (let [game-map (:map @state)
        floor-tiles (:floor-tiles game-map)
        entities (entities-by-pos-mem (-> @state :entities))
        player (-> @state :entities :player)
        player-pos (:pos player)
        player-inventory (:inventory player)
        combatants (:combatants @state)]
    [:span#game
     [:div {:ref #(install-arrow-key-handler state %)}
      (for [y (range (- (second player-pos) visible-dist)
                     (+ (second player-pos) visible-dist))]
        [:div.row {:key y}
         (for [x (range (- (first player-pos) visible-dist)
                        (+ (first player-pos) visible-dist))]
           (let [dist (distance-sq player-pos [x y])
                 opacity (cond
                           (> dist visible-dist-sq) 0
                           (> dist clear-dist-sq) 0.75
                           :else 1)]
             (component-cell floor-tiles entities x y opacity)))])]
     (component-health-bars-mem player combatants)
     [component-inventory player-inventory]
     [component-help (= (:modal @state) :help)]
     [component-messages (-> @state :message :text)]]))

(defn copy-element [selector]
  (let [el (.querySelector js/document selector)]
    (->
      (js/navigator.clipboard.writeText (aget el "innerText"))
      (.then (fn [] (js/alert "copied"))))))

(defn component-tombstone [state]
  (let [{:keys [outcome entities]} @state
        {:keys [player]} entities
        {:keys [inventory]} player]
    [:div#tombstone
     [:p "Rogule " (date-token)]
     [:div "Score: " (apply + (map :value inventory)) " / " (:max-score @state)]
     [:p
      (tile (load-sprite :elf) "you") " "
      (name outcome) " "
      (if (= outcome :ascended)
        (tile (load-sprite :glowing-star))
        (tile (load-sprite :skull-and-crossbones)))]
     [:p
      (for [e (sort-by (juxt :value :name) inventory)]
        [:span (tile-mem (:sprite e) (:name e) {:width "48px"})])]
     [:button {:autoFocus true
               :on-click #(reset! state (make-level initial-state size))}
      "restart"]
     [:button {:on-click #(copy-element "#tombstone")} "share"]]))

(defn component-main [state]
  (if (:outcome @state)
    [component-tombstone state]
    [component-game state]))

(defn key-handler [ev]
  (let [code (aget ev "keyCode")]
    (print "keyCode" code)
    (case code
      81 (reset! state (make-level initial-state size))
      191 (swap! state update-in [:modal] #(when (not %) :help))
      27 (swap! state dissoc :modal)
      nil)))

(defn start {:dev/after-load true} []
  (rdom/render [component-main state]
               (js/document.getElementById "app")))

(defn main! []
  (seedrandom (str "Rogule-" (date-token)) #js {:global true})
  (reset! state (make-level initial-state size))
  (.addEventListener js/window "keydown" #(key-handler %))
  (start))
