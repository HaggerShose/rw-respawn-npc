# Guard ideas

Core now: spawn -> walk to post -> arrive -> turn -> lock.

- ~~Player gate: if no player within a distance the constant checks should be paused or extended~~ -> maybe a player gate for combat mode checks but i need to think more about this.
- ~~Observe first (no code yet): respawn and guard `moveTo` when no player is nearby / chunk unloaded. Suspect the NPC stays put with an outstanding `moveTo` while watches keep polling. Later: pause watches or gate on player proximity -- decide after watching a live server.~~
Beobachtungen:  
  - wenn ich weit weg bin geht ein npc vom pending in den walking state
  - nach einer zeit geht er in den at post state
  - wenn ich mich wider in die Nähe bewege wird der npc optisch am spawn angezeigt, seine koordinaten sind allerdings am posten und nach einem reload der map wird er am posten angezeigt.
  - bei einem weiteren test beobachte ich die koordinaten des npc und es vergeht eine gewisse zeit mit koordinaten am spawn und dann springen sie auf die posten koordinaten. es scheint so als würden npcs im hintergrund eine art von primitvem movement code ausführen. was ja based ist. aber warum wird er optisch an der falschen stelle angezeigt? eventuell hat das etwas mit dem locked state zu tun?
  - ohne locked hat der npc die posten koordinaten, aber wenn man in die nähe kommt erscheint er optisch am spawn und wenn er sich dann bewegt aktualisiert sich seine position dort hin wo man ihn sieht. hmmm
  - `still.setPosition(new Vector3f(post.x(), post.y(), post.z()));` nach `still.setLocked(true);` hat das augenscheinlich gefixt.

- invent NPC states and a watcher to track and check these states (pending respawn, walking, at post, combat, stuck,...)
