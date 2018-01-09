(ns bearz.conductor.helpers)

(defn create-event-name
  "Nice helper to create a proper event. Pass store-id to call and name of event to call and function will return a
  keyword that 'dispatch' function can use to map and call needed function. Both name and store id should be strings."
  [store-id event-name]
  (keyword (str store-id "/" event-name)))
