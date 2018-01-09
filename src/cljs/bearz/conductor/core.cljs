(ns bearz.conductor.core
  (:require [rum.core :as rum]
            [bearz.conductor.store :refer [render get-id events]]
            [bearz.conductor.reducers :refer [reducers-registry]]
            [bearz.conductor.helpers :refer [create-event-name]]))

;; Conductor-alpha-dec2017
;; @author bearz
;; @dependencies 'tonsky/rum', 'cljs-ajax'
;;
;; A very simple implementation of uniflow data binding that combines 'cgql' and 'rum' libraries to provide an easy
;; framework to manage state and draw UI, but manage growing complexity of application. Consist of a few main concepts
;; described below.
;;
;;
;; STATE
;;
;; Created and managed by Conductor for a developer. State consists of a shared 'app-state' object and a collection of
;; stores.
;; Any handler has access to 'app-state' and it designed to hold application-wide information like user-info, session,
;; etc. Information that is specific to a component should be stored in a particular 'store' responsible for that logic.
;;
;;
;; STORE
;;
;; A building block of application state. Consist of properties, view, events and optional state. Must implement IStore
;; protocol. View function should be idempotent rum component that takes properties, local-state and app-state
;; and generates actual UI out of it using 'rum'. Store can expose events that other stores or components can call. More
;; details on events are below.
;;
;; Store lifecycle
;;
;; Store should be attached to a state only when it is needed to reduce overhead and memory usage. To attach store use
;; 'conductor/register-store' effect passing store as a parameter. This will call 'init' event on a store if it exist
;; after store is attached. To remove store use 'conductor/deregister-store' effect with store to remove as a parameter.
;; 'remove' function will be called before store is removed to properly remove all stores bindings it can have to browser
;; or do any before-remove actions. After store is deregistered it will be no longer able to receive any events or do
;; any actions.
;;
;;
;; EVENTS
;;
;; Events is what moving application forward. Use 'dispatch' function to trigger event from any place in application.
;; There is one limitation – it is not allowed to trigger events inside event-handlers. This is to avoid uncontrollable
;; state changes and unpredictable "half-states". If event need to be triggered use 'conductor/dispatch-after' effect.
;;
;; Event names convention
;;
;; Event name is a namespaced keyword where namespace should match store-id and name should match handler from 'events'
;; map of that store.
;; There is a special reserved event 'conductor/apply-effects' which takes effect map and reduces it. This hack comes
;; handy when some events should be dispatched before applying some effects.
;;
;;
;; EVENT-HANDLERS
;;
;; Event handler is a simple functions that are part of the store and linked to conductor by names in 'events' map of a
;; store. When called they should produce a zero or more effects which then will change application state. Functions
;; must be clean and don't produce any side-effects. It will make it easy to test them if written that way. Must return
;; effect-map as a result.
;;
;;
;; EFFECTS
;;
;; This is a place where mutations and side-effects are happening. They are essential for any customer facing application.
;; There are few default effects handlers which are defined in 'reducers' file. Any effect handler is receiving current
;; app-state as an argument and suppose to return a new app-state as a result.
;;
;;
;; HOW IT ALL WORKS?
;;
;; STATE ----> (view function) ----> User interface/Outside world
;;    ^                               |
;;    |                               |
;; REDUCERS (EFFECT-HANDLERS)         |
;;    ^                               |
;;    |                               v
;; EFFECTS <---- EVENT-HANDLERS <---- EVENTS
;;
;; Framework runs an endless loop of uni-data flow, where events are triggered by user or outside world,
;; handled by a handler producing effects. Effects are then "reduced" to compute a new application state, which in
;; turn automatically updates UI. If callbacks are required (for example for data) then they will be bind to "outside
;; world" part producing new events when happen. This flow allows to build a very decoupled application which is quite
;; easy to reason about.



;; Holds all the app-state over here. There are couple of internal variables
;; that are part of the state like layout, url, etc and a bunch of default events
;; to resolve on.
;; Anything that is related to a global state must be stored here.
;; This state should never be updated outside of state-transition functions
;;
;; Core idea: to be able to restore exact state of application from this state at
;; any moment. This will help debug/figwheel and bug reporting.
(def app-state
  ;; todo move defaults to the mutate-app-state and move state outside of this file, probably.
  (atom
    {:root-store-id nil
     :root-node     nil
     :app-state     {}                                      ;; user handlers can only change this state
     :stores        {}                                      ;; stores are "sandboxes" that manage their own states.
     :events-queue  []}))

(defn- mutate-app-state!
  "Updates app-state to a new state provided. State should never be changed outside this function."
  [new-state]
  ;; todo validate state with spec!
  (swap! app-state (fn [old-state] new-state)))             ;; is no comparison ok? I guess so, until javascript is single-threaded

(defn- apply-client-handler
  "Finds appropriate handler by event-name and applies it. When applied will add shared-state and store as an arguments.
   Returns effect produced by a handler or no-effect in case a handler hasn't found. Warning will be sent to a console
   when handler hasn't been located.

   Signature that a handler will be called with:
   [store-object client-app-state & params]
   Where:
    * store-object     – a store to which handler belongs;
    * client-app-state – shared state of application stored in 'app-state' property
    * params           - params passed to a 'dispatch' function by caller.
   "
  [event-name params]
  (let [store-name (namespace event-name)                   ;; all events must be namespaced to a pointr.conductor.store, broadcast aren't supported
        local-event-name (keyword (name event-name))
        app-state-val @app-state
        store (get-in app-state-val [:stores store-name])]
    (try
      ;; try here because store might not implement events. Safety belt for simplicity.
      (let [handler (get (events store) local-event-name)]
        (apply handler (-> params
                           ;; client events only get their app-state, not everything
                           (conj (:app-state app-state-val))
                           (conj store))))
      (catch js/Object e
        (js/console.warn (str "Unable to find handler function for event: " event-name
                              ". Skipping!"))
        {}))))

(defn- reduce-effect!
  "Reduces one effect, returning a new state. Accepts current state and a pair of values where first one is effect name
  and second is value of effect. Throws an error when handler for this effect doesn't exist to prevent from silent errors"
  [state [effect-name value]]
  (let [effect-handler (get reducers-registry effect-name)]
    (if (nil? effect-handler)
      (throw (js/Error. (str "Conductor-error: effect '" effect-name "' isn't allowed or registered"))))

    (js/console.log "Running reducer for '" effect-name "'")
    (effect-handler state value)))

(defn- reduce-effects!
  "Takes effect-map and reduces it to a new state. State mutation will only happen if all the handlers finished properly"
  [effect-map]
  (js/console.log (str "Reducing effects: ") effect-map)
  (let [new-state (reduce reduce-effect! @app-state effect-map)]
    (mutate-app-state! new-state)))


(defn dispatch
  "Dispatches events to move an application forward. Will find an appropriate handler, compute effect of it and
  reduce effects to advance the application state. It is an only way to change a state in conductor library.

  Accepts 1 or more parameters, where first must always be event name and params to pass to a handler of this event.
  Event name is a namespaced keyword where namespace should match store-id and name should match handler from 'events'
   map of that store.

  There is a special reserved event 'conductor/apply-effects' which takes effect map and reduces it. This hack comes
   handy when some events should be dispatched before applying some effects."
  [event-name & params]
  (js/console.log (str "Dispatching event: " event-name " with params: ") params)
  (let [state @app-state
        effects (if (= event-name :conductor/apply-effects)
                 (nth params 0)
                 (apply-client-handler event-name params))]
    (if-not (nil? effects)
      (reduce-effects! effects))))


(defn- schedule-dispatch
  "Uses a trick with 'js/setTimeout 0' to schedule dispatch of next functions when a browser is 'free'.
   Shouldn't be used outside of the event-queue-watcher."
  [dispatch-list]
  (js/console.log "Scheduled events for dispatch: " dispatch-list)
  (js/setTimeout (fn [e]
                   (loop [[event & rest-events] dispatch-list]
                     (apply dispatch event)
                     (if-not (nil? rest-events)
                       (recur rest-events))))
                 0))


(defn- state-change-log
  "Logging function for debug to track state changes when they happen."
  [key watched old-state new-state]
  (js/console.log "Global app state changed. New state: " new-state)
  (js/console.log "State diff: " (clojure.data/diff new-state old-state)))


(defn- event-queue-watcher
  "Listens to changes inside app-state and if sees some events in the events-queue then removes
  them from there and schedules to execute at the nearest time when execution thread is freed-up."
  [key watched old-state new-state]
  (let [events-queue (:events-queue new-state)]
    (if (> (count events-queue) 0)
      (do
        (swap! watched (fn [state] (assoc state :events-queue [])))
        (schedule-dispatch events-queue)))))


(defn init []
  "Initializes conductor library setting up all required handlers to run application."
  (add-watch app-state :state-change-log state-change-log)
  (add-watch app-state :event-queue-dispatcher event-queue-watcher))


(defn mount! [root-store dom-node]
  "Mounts root-store to an application. Root-store is a top level store that holds a root view for a current application
  state and from where all view changes will be propagated. Conductor takes care about reducing state to a new view,
  whenever state changes.

  Function params:
  * root-store – provides root-store object
  * dom-node   – node to bind view of root-store to"
  (js/console.log "Mounting app: " root-store)
  (let [state @app-state
        store-id (get-id root-store)]
    (mutate-app-state! (-> state
                           (assoc :root-store-id store-id)
                           (assoc :root-node dom-node)))
    (rum/mount (render root-store) dom-node)
    (dispatch :conductor/apply-effects {:conductor/register-store root-store})))


(defn force-redraw
  "Dev tool to redraw current screen on code update. As it is now, will break when conductor file is changed."
  []
  (let [state @app-state
        root-node (:root-node state)
        root-store (get-in state [:stores (:root-store-id state)])]
    (js/console.log state)
    (js/console.log @app-state)
    (js/console.log root-node root-store)
    (mount! root-store root-node)))