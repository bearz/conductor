(ns bearz.conductor.store)


(defprotocol IStore
  "Protocol that defines common operations of Store. Store – is a defined piece of application's business logic.
   It is suggested that you define a record for a store that implements IStore protocol and keeps properties of a store.
   Conductor is responsible for binding all the stores together."

  (render [this]
          "Returns a rum component that represents UI. Shouldn't under any circumstances trigger any side-effects,
          just translate state to a view component then that get rendered by reagent.")

  (get-id [this]
          "Returns id of a store, must be unique across application since will be used to store data in a
          data-store. Please, keep in mind, that sometimes you can have multiple stores with different properties.")

  (events [this]
          "Returns a map of events registered with this store. Make sure that all names are
          namespaced with pointr.conductor.store id. You can create your own events or add handlers to standard ones.
          See doc for standard events")

  )
