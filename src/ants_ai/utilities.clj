(ns ants-ai.utilities
    "Useful functions to help us deal with the world"
    (:require [clojure.string :as string]
              [ants-ai.defines :as defines]
              [ants-ai.gamestate :as gamestate]
              [ants-ai.gameinfo :as gameinfo]))

(defn safe-abs
  "Correctly handles calling abs on BigInts by coercing back to an int"
  [val]
  (Math/abs (int val)))

(defn debug-log
  "Log something to the console for us to go through later"
  [& message]
  (when defines/logging-enabled
    (binding [*out* (if (nil? defines/*log-file*)
                        *err*
                        defines/*log-file*)]
      (apply println message)
      (flush))))

(defn unit-distance
  "Get the vector distance between two points on a torus. Negative deltas are preserved."
  [location-one location-two]
  (let [[dx dy] (map - location-two location-one)
        [adx ady] (map #(safe-abs %) [dx dy])
        [adx2 ady2] (map #(- (gamestate/game-info %) %2) [:rows :cols] [adx ady])
        fx (if (<= adx adx2)
             dx
             (* adx2 (/ (- dx) adx)))
        fy (if (<= ady ady2)
             dy
             (* ady2 (/ (- dy) ady)))]
    [fx fy]))

(defn distance-no-sqrt
  "Get the euclidean distance between two locations on a torus, squared"
  [location location-two]
  (let [[dx dy] (unit-distance location location-two)]
    (+ (Math/pow dx 2) (Math/pow dy 2))))

(defn distance
  "Get the euclidean distance between two locations on a torus"
  [location location-two]
  (Math/sqrt (distance-no-sqrt location location-two)))

(defn contains-ant?
  "See if the given location is in the given ants array"
  [ants cur]
  (some #(let [[r c p] %]
            (= [r c] cur))
        ants))

(defn passable?
  "Deteremine if the given location can be moved to. If so, loc is returned."
  [loc]
  (when (and (not (contains? (gamestate/water) loc))
             (not (contains? (gamestate/food) loc))                   ; Can't walk onto newly spawned food
             (not (contains? (gamestate/my-hills) loc)))              ; We shouldn't move onto our own hills
    loc))

(defn move-ant
  "Move the ant in the given direction and return the new location"
  [ant dir]
  (let [dir-vector (defines/direction-offsets dir)
        rows (gameinfo/map-rows)
        cols (gameinfo/map-columns)
        [r c] (map + ant dir-vector)]
    [(cond
       (< r 0) (+ rows r)
       (>= r rows) (- r rows)
       :else r)
     (cond
       (< c 0) (+ cols c)
       (>= c cols) (- c cols)
       :else c)]))

(defn valid-move?
  "Check if moving an ant in the given direction is passable. If so,
  return the location that the ant would then be in."
  [ant dir occupied-locations]
  (let [the-loc (move-ant ant dir)]
    (when (and (passable? the-loc)
              (not (contains? occupied-locations the-loc)))
      the-loc)))

(defn direction
  "Determine the directions needed to move to reach a specific location.
  This does not attempt to avoid water. The result will be a collection
  containing up to two directions."
  [location location-two]
  (let [[dr dc] (unit-distance location location-two)
        row (if-not (zero? dr)
                    (/ dr (safe-abs dr))
                    dr)
        col (if-not (zero? dc)
                    (/ dc (safe-abs dc))
                    dc)]
    (set (filter #(not (nil? %))
            [(defines/offset-directions [row 0])
             (defines/offset-directions [0 col])]))))

(defn coalesce
  "Replace nil values with the default."
  [value default]
  (if (nil? value)
    default
    value))

