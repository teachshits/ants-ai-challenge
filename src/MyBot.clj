(ns MyBot
    "The core of our bot"
    (:require [clojure.set :as set]
              [ants-ai.interface :as interface]
              [ants-ai.defines :as defines]
              [ants-ai.diffusion :as diffusion]
              [ants-ai.utilities :as utilities]
              [ants-ai.gamestate :as gamestate]
              [ants-ai.gameinfo :as gameinfo]
              [ants-ai.core :as core]))

; Move functions take only an ant.
; They return a set of directions that it would like to move this turn (possibly nil/empty)

(defn move-in-random-direction
  "Pick a random valid direction"
  [ant valid-directions last-move]
;(utilities/time-method defines/*timing-move-in-random-direction*
  (if (and (contains? valid-directions last-move)
            (not (utilities/seeded-rand-chance 20)))  ; 5% of the time forget what we were doing
    #{last-move}                                  ; Prefer the direction the ant was already going in
    valid-directions))
;)

;(defn move-towards-food-diffusion
;  "Move towards the closest piece of food the ant can see"
;  [ant _ _]
;  (let [[_ dir] (get @defines/*food-diffusion-map* ant)]
;    (when dir
;      #{dir})))

;(defn move-towards-food-res
;  "Move towards the closest piece of food the ant can see, with reservations"
;  [ant _ _]
;  (when (not-empty (gamestate/food))
;    (let [food-distances (map #(vector % (utilities/distance-no-sqrt ant %)) (gamestate/food))
;          food (sort-by #(second %) (filter #(<= (second %) (gameinfo/view-radius-squared)) food-distances))
;          best-spot (first (some #(let [reservations (@defines/*food-reservations* (first %))]  ; Gets first food square
;                                    (when (< reservations 2)                                    ; with less than 2 ants going
;                                      %))                                                       ; after it
;                                  food))]
;      (when best-spot
;        (swap! defines/*food-reservations* #(assoc % best-spot (inc (% best-spot))))        ; Make a reservation
;        (interface/visualizer-color :food)
;        (interface/visualize-arrow ant best-spot)
;        (utilities/direction ant best-spot)))))                                             ; Send the move on

(defn move-towards-food-classic
  "Move towards the closest piece of food the ant can see"
  [ant _ _]
;(utilities/time-method defines/*timing-move-towards-food-classic*
  (when (not-empty (gamestate/food))
    (let [food-distances (map #(vector % (utilities/distance-no-sqrt ant %)) (gamestate/food))
          food (sort-by #(second %) (filter #(<= (second %) (gameinfo/view-radius-squared)) food-distances))
          visible-food (filter #(utilities/is-line-of-site-clear? ant (first %) utilities/water-test) food)
          best-spot (first (first visible-food))]
      (when best-spot
;        (interface/visualizer-color :food)
;        (interface/visualize-arrow ant best-spot)
        (utilities/direction ant best-spot)))))                                             ; Send the move on
;)

(defn move-away-from-enemy
  "Move away from the closest enemy"
  [ant _ _]
  ; This rule doesn't apply if we are in visible range of one of our hills, have no hills, or know of no enemies
;(utilities/time-method defines/*timing-move-away-from-enemy*
  (if (or (empty? (gamestate/enemy-ants))
          (empty? (gamestate/my-hills))
          (some #(<= (second %) (gameinfo/view-radius-squared))
              (map #(vector % (utilities/distance-no-sqrt ant %)) (gamestate/my-hills))))
    nil
    ; The rule also doesn't apply if there are no enemy ants
    (let [ant-distances (map #(vector % (utilities/distance-no-sqrt ant %)) (gamestate/enemy-ants))
          ants (sort-by #(second %) (filter #(<= (second %) (max 64 (gameinfo/attack-radius-squared))) ant-distances))]
      (when (not-empty ants)
        (let [worst-ant (first (first ants))]
;          (interface/visualizer-color :ant)
;          (interface/visualize-arrow worst-ant ant)
          (set/difference defines/directions (utilities/direction ant worst-ant)))))))
;)

(defn move-to-emergency-defense
  "Reinforce our hill if any enemy is near"
  [ant _ _]
;(utilities/time-method defines/*timing-move-to-emergency-defense*
  ; This rule doesn't apply if we don't know of any enemies, have no hills, or are on a hill
  (if (or (empty? (gamestate/enemy-ants))
            (empty? (gamestate/my-hills))
            (contains? (gamestate/my-hills) ant))
    nil
    ; Find our closest hill (line of site)
    (let [hill-distances (map #(vector % (utilities/distance-no-sqrt ant %)) (gamestate/my-hills))
          hills (sort-by #(second %) hill-distances)
          visible-hills (filter #(utilities/is-line-of-site-clear? ant (first %) utilities/water-test) hills)
          closest-hill (first (first visible-hills))]
      (when closest-hill    ; Check to see if any enemies are close
        (let [enemy-distances (map #(vector % (utilities/distance-no-sqrt closest-hill %)) (gamestate/enemy-ants))
              bad-ants (sort-by #(second %) (filter #(<= (second %) (gameinfo/view-radius-squared)) enemy-distances))
              with-line-of-attack (filter #(utilities/is-line-of-site-clear? closest-hill (first %) utilities/water-test) bad-ants)]
          (when (not-empty with-line-of-attack)   ; Head back
            (interface/visualizer-color :defend)
            (interface/visualize-arrow ant closest-hill)
            (utilities/direction ant closest-hill)))))))
;)

(defn move-to-defend-our-hills
  "Reinforce our hill if any enemy is near"
  [ant _ _]
;(utilities/time-method defines/*timing-move-to-defend-our-hills*
  (cond
    ; This rule doesn't apply if we don't have any hills, or don't have a defense strategy
    (or (nil? @defines/*current-defense*)
        (empty? (gamestate/my-hills)))
      nil
    ; If we are in one of the defense positions, we're set and shouldn't move
    ; Return the special :none direction to abort further move checks
    (contains? @defines/*positions-to-fill* ant)
      :none
    ; Find the closest defense position that needs filling
    :else
      (let [spot-distances (map #(vector % (utilities/distance-no-sqrt ant %)) @defines/*positions-unfilled*)
            spots (sort-by #(second %) (filter #(<= (second %) 9) spot-distances))        ; Within 3 squares
            visible-spots (filter #(utilities/is-line-of-site-clear? ant (first %) utilities/water-test) spots)
            closest-spot (first (first visible-spots))]
        (when closest-spot    ; Check to see if any spots close
          (interface/visualizer-color :reinforce)
          (interface/visualize-arrow ant closest-spot)
          (utilities/direction ant closest-spot)))))
;)

(defn move-to-capture-hill
  "Move towards an enemy hill the ant can see"
  [ant _ _]
;(utilities/time-method defines/*timing-move-to-capture-hill*
  (when (not-empty (gamestate/enemy-hills))
    (let [hill-distances (map #(vector % (utilities/distance-no-sqrt ant %)) (gamestate/enemy-hills))
          hills (sort-by #(second %) (filter #(<= (second %) (gameinfo/view-radius-squared)) hill-distances))
          visible-hills (filter #(utilities/is-line-of-site-clear? ant (first %) utilities/water-test) hills)
          best-spot (first (first visible-hills))]
      (when best-spot
        (interface/visualizer-color :hill)
        (interface/visualize-arrow ant best-spot)
        (utilities/direction ant best-spot)))))
;)

(defn find-move-through-functions
  "Run each function in turn for the ant, return the first non-nil direction we find that's valid"
  [ant valid-directions valid-directions-with-back ants-last-move]
  (when (not-empty valid-directions))
    (loop [functions-to-run {move-to-defend-our-hills :defense    ; Setup standing defenses around our hills
                            move-to-emergency-defense :e-defense  ; Don't let them take our hills!
                            move-to-capture-hill :capture         ; First capture any hills we can see
                            move-away-from-enemy :run-away        ; Get away from nearby enemy ants
                            move-towards-food-classic :food       ; Then go for the closest food
                            move-in-random-direction :random}]    ; Nothing better? Go in a random direction
      (if (not-empty functions-to-run)
        (let [the-function-stuff (first functions-to-run)
              the-function (key the-function-stuff)
              the-function-purpose (val the-function-stuff)
              result (apply the-function [ant valid-directions ants-last-move])]
          (if (= :none result)
            nil                                                     ; Shortcut to get out without moving if neccessary
            (if (empty? result)
              (recur (rest functions-to-run))                       ; No decision, try the next function
              (let [moves-to-choose-from (set/intersection result (if (= the-function-purpose :e-defense)
                                                                      valid-directions-with-back
                                                                      valid-directions))  ; In e-defense, we can reverse
                    dir (when (not-empty moves-to-choose-from)
                          (utilities/seeded-rand-nth (vec moves-to-choose-from)))]
                (if dir                                               ; Was one of the moves valid?
                  (do
                    (utilities/debug-log "Ant at " ant " doing " the-function-purpose ", going " dir)
                    (interface/visualize-info ant (str "Reason: " the-function-purpose))
;                    (interface/visualize-info ant (str "Valid moves: " valid-directions))
;                    (interface/visualize-info ant (str "Valid moves back: " valid-directions-with-back))
;                    (interface/visualize-info ant (str "Result: " result))
;                    (interface/visualize-info ant (str "Moves to choose from: " moves-to-choose-from))
;                    (interface/visualize-info ant (str "Direction: " dir))
;                    (interface/visualize-info ant (str "Last move: " ants-last-move))
                    dir)
                  (recur (rest functions-to-run))))))))))

(defn process-ant
  "Take the given ant and figure out a move for them, returned as [ant dir result]"
  [ant occupied-locations]
  (let [valid-moves (filter #(utilities/valid-move? ant % occupied-locations) defines/directions)  ; Ways ant could move
        ants-last-move (@defines/*ant-last-moves* ant)                          ; The way the ant last moved
        ants-way-back (defines/opposite-directions ants-last-move)
        valid-directions (if (= (list ants-way-back) valid-moves)               ; Be sure that if we only have one valid
                            (set valid-moves)                                   ; move that it's always available
                            (set (filter #(not= % ants-way-back) valid-moves))) ; This is to allow us to not move back
        dir (find-move-through-functions ant valid-directions (set valid-moves) ants-last-move)
        result (when dir
                (utilities/move-ant ant dir))]
    (cond
      (nil? dir)                                                  ; No valid moves? Stand still
        [ant nil ant]
      :else                                                       ; We're good
        [ant dir result])))

(defn reset-per-turn-atoms
  "Resets the atoms that only make sense during a turn"
  []
;  (reset! defines/*food-map* (diffusion/diffuse-across-map (gamestate/food)
;                                                            (gamestate/water)
;                                                            9))

;  (reset! defines/*timing-move-to-defend-our-hills* 0)
;  (reset! defines/*timing-move-to-emergency-defense* 0)
;  (reset! defines/*timing-move-away-from-enemy* 0)
;  (reset! defines/*timing-move-towards-food-classic* 0)
;  (reset! defines/*timing-move-in-random-direction* 0)
;  (reset! defines/*timing-move-to-capture-hill* 0)
;  (reset! defines/*timing-finding-moves* 0)
  (reset! defines/*current-defense* (utilities/determine-defense-strategy))       ; Pick our current strategy
  (reset! defines/*positions-to-fill* (if (nil? @defines/*current-defense*)
                                        #{}
                                        (loop [spots #{}                            ; Figure out all the cells we need
                                             hills-to-go (gamestate/my-hills)]    ;   for that strategy to work
                                        (if (empty? hills-to-go)
                                          spots
                                          (let [hill (first hills-to-go)
                                                others (rest hills-to-go)
                                                map-for-hill (@defines/*defense-positions* hill)]
                                            (recur (apply conj spots (map-for-hill @defines/*current-defense*))
                                                    others))))))
  ; Figure out the cells left to fill after taking into account the ants that may already be on some of them
  (reset! defines/*positions-unfilled* (apply disj @defines/*positions-to-fill* (gamestate/my-ants)))
  )
;  (reset! defines/*food-reservations* (loop [reservations {}
;                                             food-to-go (gamestate/food)]
;                                        (if (empty? food-to-go)
;                                          reservations
;                                          (recur (assoc reservations (first food-to-go) 0) (rest food-to-go))))))

(defn process-ants-for-moves
  "Process each ant in turn, gathering up their moves in the form [loc dir result]"
  []
  (utilities/debug-log "")
  (utilities/debug-log "New turn")
  (reset-per-turn-atoms)
  (utilities/debug-log "Strategy: " @defines/*current-defense*)
  (utilities/debug-log "Unfilled: " @defines/*positions-unfilled*)
  (let [start-time (System/currentTimeMillis)
        result  ; The loop body below is the definition of this
    (loop [ants (gamestate/my-ants)   ; Ants we're processing
           moves []]                  ; Moves we'll be making (a list because order matters)
      (cond
        (> (- (System/currentTimeMillis) start-time) (* 0.90 (gameinfo/turn-time)))
          (do   ; CRUD! Out of time. Don't push it
            (utilities/debug-log "Skipping the rest of the ants becuase we're out of time")
            moves)
        (empty? ants)                     ; Out of ants? We're done
          (do
;            (utilities/debug-log "Move to defense positions: " @defines/*timing-move-to-defend-our-hills* "ns")
;            (utilities/debug-log "Move to emergency defense: " @defines/*timing-move-to-emergency-defense* "ns")
;            (utilities/debug-log "Move to capture hill: " @defines/*timing-move-to-capture-hill* "ns")
;            (utilities/debug-log "Move away from enemies: " @defines/*timing-move-away-from-enemy* "ns")
;            (utilities/debug-log "Move towards food: " @defines/*timing-move-towards-food-classic* "ns")
;            (utilities/debug-log "Move in random directions: " @defines/*timing-move-in-random-direction* "ns")
;            (utilities/debug-log "Finding all moves: " @defines/*timing-finding-moves* "ns")
;            (utilities/debug-log "Move overhead: " (- @defines/*timing-finding-moves* @defines/*timing-move-to-emergency-defense* @defines/*timing-move-to-capture-hill* @defines/*timing-move-away-from-enemy* @defines/*timing-move-towards-food-classic* @defines/*timing-move-in-random-direction*) "ns")
            moves)
         :else
          (let [ant (first ants)                                              ; Ant we're working with
                occupied-locations (into (rest ants) (map #(last %) moves))   ; "Occupied" locations
                ants-move (utilities/time-method defines/*timing-finding-moves*
                                                 (process-ant ant occupied-locations))  ; Figure out a move
                result (last ants-move)]
            (utilities/debug-log ant " moving " (second ants-move) " to " result)
            (recur (rest ants)                    ; Ants left to process
                    (conj moves ants-move)))))   ; Moves updated with our new move
        ; Continuing with the bindings in the let here
          end-time (System/currentTimeMillis)
          d (- end-time start-time)]
    (utilities/debug-log "Full turn: " d "ms")
;    (utilities/debug-log "Full overhead: " (- d (/ @defines/*timing-finding-moves* 1000000)) "ms")
    result))

(defn simple-bot []
  "Core loop for the bot"
  (interface/setup-visualizer)
  (reset! defines/*defense-positions* (utilities/calculate-defense-strategies))
  (reset! defines/*seeded-rng* (new java.util.Random (gameinfo/rand-seed)))
  (doseq [[ant dir res] (process-ants-for-moves)]
    (when dir
      (interface/issue-move ant dir)                      ; Issue the move to the server
      (swap! defines/*ant-last-moves* assoc ant nil)      ; Forget the last move at the ant's old position
      (swap! defines/*ant-last-moves* assoc res dir))))   ; Remember which way the ant went to their new position

(if defines/logging-enabled
  (binding [defines/*log-file* (java.io.FileWriter.
                                  "/Users/michael/Programming/Ants AI Challenge/tools/game_logs/my-log.txt"
                                  false)]
    (core/start-game simple-bot)
    (.close defines/*log-file*))
  (core/start-game simple-bot))
