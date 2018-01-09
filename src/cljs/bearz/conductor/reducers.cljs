(ns bearz.conductor.reducers
  (:require [bearz.conductor.store :refer [get-id]]
            [ajax.core :as ajax]
            [bearz.conductor.helpers :refer [create-event-name]]))

;; Effects are always taking two params: app-state and params.
;; They can do whatever they want (if they do side-effects, make sure to mark them with "!" at the
;; end of the function) but must return a new app-state as a result of this handler. Return
;; the passed state if nothing has changed.


(defn- add-dispatch-to-queue
  "Accepts 'events' that need to be dispatched after current handlers are finished. Events must be array of events to
  dispatch. Event to dispatch is presented with array where first item is a name of event and all others are params.
  It will be passed unchanged to the 'dispatch' function.

  e.g. [[:conductor/apply-effects {conductor/deregister-store store1}]
        [:cgql/request (me-request)]]"
  [app-state events]
  (let [events-queue (:events-queue app-state)]
    (assoc app-state :events-queue (into events-queue events))))

(defn- register-store
  "Accepts store as a second param and registers it to state. Triggers 'init' event on a store if that one exists
  after store is registered."
  [app-state store]
  (let [screen-id (get-id store)
        init-event-name (create-event-name screen-id "init")]
    (if (contains? (:stores app-state) screen-id)
      (js/console.warn (str "Replacing pointr.conductor.store that is already has been registered before: " screen-id)))

    (-> app-state
        (assoc-in [:stores screen-id] store)
        (add-dispatch-to-queue [[init-event-name]]))))


(defn- dissoc-store
  "Actually removes store from state. After that this store can't execute any events. Avoid calling it directly, use
  'deregister-store' instead."
  [app-state store]
  (update-in app-state [:stores] dissoc (get-id store)))

(defn- deregister-store
  "Calls remove event on a store and removes store from store-registry. "
  [app-state store]
  (add-dispatch-to-queue app-state [[(create-event-name (get-id store) "remove")]
                                    [:conductor/apply-effects {:conductor/_dissoc-store store}]]))

(defn query-cgql!
  "Sends ajax request to cgql endpoint on server and triggers handler-id on response-received.
  Accepts list structure with cgql query and namespaced keyword representing who should get a
  response."
  [query handler-id]
  (js/console.log (str "Sending server request: " query " binding handler id: " handler-id))
  (ajax/ajax-request
    {:method          :post
     :uri             "/cgql"
     :params          {:query (pr-str query)}
     :response-format (ajax/raw-response-format)
     :format          (ajax/url-request-format)
     :handler         (fn [[ok response]]
                        (js/console.log response)
                        (pointr.conductor.core/dispatch handler-id
                                                        (if ok "success" "failure")
                                                        (if ok (read-string response) response)))}))

(defn- cgql-request
  "Triggers request to a server with supplied query and registers a callback event to call when response arrived.
  Accepts a pair of arguments as parameters: [cgql query itself, id of a handler to call with a result]."
  ;; todo move as a client reducer or to a plugin
  [app-state [query handler-id]]
  (query-cgql! query handler-id)
  app-state)

(defn- update-store
  "Updates store with provided store-id with a newly supplied state."
  [app-state [store-id new-state]]
  (js/console.log "Updating local content of a pointr.conductor.store: " store-id)
  (let [screen (get-in app-state [:stores store-id])
        ;; there is a convention that if pointr.conductor.store has local state, it keeps state in atom in "state" argument.
        data-atom (:state screen)]
    (if (nil? data-atom)
      (throw (js/Error. (str "Screen with id: " store-id " doesn't have data-atom. Can't change its state"))))

    (reset! data-atom new-state))
  app-state)


(def reducers-registry
  {:conductor/register-store     register-store
   :conductor/deregister-store   deregister-store
   :conductor/dispatch-after     add-dispatch-to-queue
   :conductor/change-store-state update-store
   :conductor/_dissoc-store      dissoc-store
   :cgql/request                 cgql-request})