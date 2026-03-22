# DragonFix
This plugin fixes the crash with EaglerCraft on 1.12.2 ran on a server when fighting the ender dragon (fixes the AreaEffectCloud)

## Explaination
Basically, the entity `AreaEffectCloud` makes EaglerCraft crash if the player is in a server running [EaglerXServer](https://github.com/lax1dude/eaglerxserver) (don't ask me why, I don't know).
So this plugin basically intercept this entity, destroys the packet sent to players about it, and then simulates a fake Dragon breath (with damage based on a radius + particle effect where the ball entity landed)

i wrote this rapidly ; it's not perfect ; feel free to open a PR !
