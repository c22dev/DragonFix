# DragonFix
This Spigot Plugin fixes the crash with EaglerCraft on 1.12.2 ran on a server when fighting the ender dragon (fixes the AreaEffectCloud)

## Explaination
Basically, the entity `AreaEffectCloud` makes EaglerCraft crash if the player is in a server running [EaglerXServer](https://github.com/lax1dude/eaglerxserver).
So this plugin basically intercept this entity, destroys the packet sent to players about it, and then simulates a fake Dragon breath (with damage based on a radius + particle effect where the ball entity landed)

i wrote this rapidly ; it's not perfect ; feel free to open a PR !

## Technical deep dive

### Why does it crash ?
when the enderdragon spits a fireball (`EntityDragonFireball`), it hits the ground and calls `onImpact()` ; inside that method, NMS directly spawns an `EntityAreaEffectCloud` (the purple thingy). as eaglercraft is based on 1.8 and that `EntityAreaEffectCloud` was  added in 1.9 it naturally doesnt work well.
The WasmGC runtime used by EaglerCraft cant cast the entity to its expected type inside `shouldIgnoreRadius()` at `EntityAreaEffectCloud.java:165` (explains the `ClassCastException`); as i have no idea on to how to patch this client side, i thought i would do it server side instead

### Let's just use event cancelation ?

the obvious approach for me was to try and cancel `EntitySpawnEvent`. the little issue is that Spigot fires this event, but by the time the handler runs spigot has alreday called `WorldServer.addEntity()` which itself calls `EntityTracker.track()` ; this calls `scanPlayers()` which directly sends a `PacketPlayOutSpawnEntity` to all nearby players...... and EaglerXServer translates and forwards the packet to the WebSocket client synchronously within the same tick so this wouldnt work.

### Replacing NMS EntityTracker

therefore we need to intercept the entity before `track()` is called ; to give you a quick idea of how `WorldServer.addEntity()` works :

```
WorldServer.addEntity()
  └─ World.b() / WorldManager.a() // entity added to world list
       └─ EntityTracker.track(entity) // THIS sends the spawn packet
            └─ EntityTrackerEntry.scanPlayers()
                 └─ PacketPlayOutSpawnEntity // all nearby players -> EaglerXServer -> client crash
```

sooo we need to replace `WorldServer.tracker` with a custom `FilteringTracker` that overrides `track()` ; if the entity is an `EntityAreaEffectCloud` we set `entity.dead = true` (so its like cleaned up next tick) and return without calling `super.track()` ; as `super.track()` is never called, `scanPlayers()` is never called either, the packet is never constructed, EaglerXServer never sees it, and the client never crashes (yayyyyyy!!!)

the little thing that's annoying is instantiating `FilteringTracker` w/o calling any `EntityTracker` constructor bc the constructor signature is different on every build. for this we can just use `sun.misc.Unsafe.allocateInstance()`
then we shallwo-copy every instance field from the original `EntityTracker` into our `FilteringTracker` using reflection 

### Simulating damage

bc we kill the AreaEffectCloud before it exists from the client's perspective, we also lose the actual damage it would deal (and thats annoying cuz it makes beating the dragon a lot easier). So when `track()` intercepts an AreaEffectCloud, before marking it dead we read its position (`locX/Y/Z`) and radius directly from the NMS entity fields via reflection

we then schedule a Bukkit `runTaskTimer` that runs every tick for 200 ticks (the AreaEffectCloud lifetime), deals 6 damage to any player within the radius, and spawns `DRAGON_BREATH` (this works surprisingly) particles to show where the zone is

---

i wrote this rapidly ; it's not perfect ; feel free to open a PR !
