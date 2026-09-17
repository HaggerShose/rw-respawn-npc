# Guard ideas

Core now: spawn -> walk to post -> arrive -> turn -> lock.

- ~~Player gate: if no player within a distance the constant checks should be paused or extended~~ Combat proximity bands: idle 30s player-to-post, medium 2s at 160m, fast 0.25s at 56m + `isAlerted` -> combat, 12s until `!isAlerted` then return-to-post.
- ~~Observe first (no code yet): respawn and guard `moveTo` when no player is nearby / chunk unloaded. Suspect the NPC stays put with an outstanding `moveTo` while watches keep polling. Later: pause watches or gate on player proximity -- decide after watching a live server.~~
Beobachtungen:  
  - wenn ich weit weg bin geht ein npc vom pending in den walking state
  - nach einer zeit geht er in den at post state
  - wenn ich mich wider in die Nähe bewege wird der npc optisch am spawn angezeigt, seine koordinaten sind allerdings am posten und nach einem reload der map wird er am posten angezeigt.
  - bei einem weiteren test beobachte ich die koordinaten des npc und es vergeht eine gewisse zeit mit koordinaten am spawn und dann springen sie auf die posten koordinaten. es scheint so als würden npcs im hintergrund eine art von primitvem movement code ausführen. was ja based ist. aber warum wird er optisch an der falschen stelle angezeigt? eventuell hat das etwas mit dem locked state zu tun?
  - ohne locked hat der npc die posten koordinaten, aber wenn man in die nähe kommt erscheint er optisch am spawn und wenn er sich dann bewegt aktualisiert sich seine position dort hin wo man ihn sieht. hmmm
  - `still.setPosition(new Vector3f(post.x(), post.y(), post.z()));` nach `still.setLocked(true);` hat das augenscheinlich gefixt.

- ~~away -> walk back to post: slow 10s tick in GuardService (living away only). Near 1m -> fast poll; 30s walk timeout re-issues moveTo. Combat / player-proximity arming later.~~
- invent more NPC states and a watcher to track and check these states (pending respawn, walking, at post, ~~combat~~, stuck,...)
- Combat arming uses player-to-post distance to enter medium. A guard fighting far from its post (player at the NPC, post hundreds of meters away) never reaches the fast `isAlerted` check. Fine for now: spawn and post must stay close because there is no pathfinding. Later: use the live NPC position for walking/away guards.
