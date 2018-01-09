# conductor
Simple state management framework for ClojureScript and rum, with support of cgql

## Disclaimer
It is not intended for use in production and more like an exercise. 

## Why one more framework?
In my quest to learn Clojure I created 'cgql' (see other repository) and now needed some front-end solution to try it on. 
Even though there are plenty of good frameworks there (Citrus, Reagent, Om, Reframe) I felt myself keep going back to their docs all the 
time. I wasn't able to wrap my head around why do you need all of those handlers, reducers, dispatching queues (in a single-thread environment?), etc.
So, I decided to "reduce" my experience as a front-end developer I've been before into a simple, yet powerful, framework to manage front-end application
state. The result is heavily inspired by frameworks above (with Citrus probably the closest in implementation) but overcomes dispatching queues
and single-state-in-shared-map problems (and probably adding much more other problems :)), thus removing two components and being much simpler. 

## How it works? 
In short, you create a "Store" that manages some part of your logic. Inside it has a state, components, and handlers. Then you register this store 
to Conductor and keep it there until it is needed. As soon as you done, you can deregister store and free up memory and unneeded handlers. 
Use 'dispatch' function to trigger handlers and produce effects as a result of those handlers. Conductor will reduce effects to advance 
application's state further. Rum will take care of reflecting state into a view (make sure to use rum/reactive mixin for your components).

## Dispatching events from handler
You shouldn't dispatch events directly from handler. The whole idea of Flux/Redux and uni-directional data-flow is against that. We all fight
for pure functions here, do you remember? But in a real world, you'll quickly face a situation where you'll need to do that. In conductor, 
you can achieve that by adding 'conductor/dispatch-after' effect. This will do exactly what names says. It will reduce effects produced by your 
current handler and schedule for dispatch your events from 'dispatch-after'. Why schedule? To avoid potential situation when your events
will cause a long chain of events capturing execution thread for a while, conductor uses a 'js/setTimeout 0' trick to return browser execution 
thread. If a browser doesn't need to do animations, requests or whatever fancy stuff your program does, then your events will be fired almost
immediately. Otherwise, let the "big man" to do his job and as soon as the thread is free again, your events will be executed. Hey, it might be 
your long awaited response sitting there! 

## What's next? 
I don't know if it would ever make it to a real framework, since the gap of features required to complete it is quite big and there are already 
tons of good solutions out there, proven with time and community. But it was a fun learning exercise and thought project, and I plan to use 
it more on pet-projects. I'll update the doc if something changes. 

## Docs
There are a lot of comments in core.cljs file that explain in more details how it works.
