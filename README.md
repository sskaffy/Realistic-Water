# Realistic Water
A Minecraft mod that adds a lot of cool, realistic stuff from Minecraft Blender animations.

![Project Screenshot](images/2.png)
![Project Screenshot](images/1.png)

#

How to use:

1. Download the latest version from: https://github.com/sskaffy/Realistic-Water/releases/
2. Install Fabric (Tutorial: https://docs.fabricmc.net/players/installing-fabric/)
3. Put the mod to the mods folder
4. Open the game and set the Graphics API in the Video Settings to Vulkan and then restart the game
5. Search up "Realistic" in the creative inventory to look at all the items from the mod
6. Use the "Realistic Convertor" to convert boats, redstone, wobbly blocks, or any full blocks into realistic blocks that have gravity
8. Grab a realistic block with right click (or shift-right click if normal right click has an interaction with that block)
9. Grab grass, leaves, kelp with right click or shift-right click for grabbing more at once
10. Remove realistic things: "/water remove" to remove water and "/water remove <block>" for everything else, including "/water remove fire" (op only) 
11. Use "Water Pump" to move water in ways it couldn't flow by itself (has to be powered by redstone)
12. Use "Realistic Water Drain" to drain realistic water
13. Customize most things about how things look/move in "/water gui" (or just "/water" if you aren't op)
14. Turn on Realistic Ocean in "/water" or in sodium settings (this makes oceans look realistic and adds realistic physics to the water)
15. Use shaders with Vitrail! Any OF/Iris shaders (even Complementary Shaders) will work, download Vitrail from: https://modrinth.com/mod/vitrail-shaders

#

### What the mod contains:

- Realistic water
- Small Bucket of Realistic water (puts down only 1 block of realistic water instead of an infinite source)
- Realistic water boiling, moving with pumps, ultra realistic physics, reacting to entities, swim in it, react to realistic blocks
- Realistic blocks (can be obtained by right clicking a Realistic Convertor and putting any block inside)
- Realistic blocks can be grabbed with right click, to bypass how heavy things you can grab turn on "OP Grabbing"
- Realistic grass
- Realistic grass can be grabbed with right click or shift-right click for grabbing multiple at once
- Realistic tree leaves
- Realistic tree leaves can be grabbed with right click or shift-right click for grabbing multiple at once
- Realistic boat, works the best with realistic water, can be obtained via the Realistic Convertor
- Realistic lava, burns things, converts into cobblestone on realistic water contact, burns flameable realistic blocks, burns leaves
- Realistic sand
- Realistic leaves
- Realistic slime
- Wobbly slime and honey
- Wobbly slime and honey react to realistic blocks, players, entities
- Wobbly slime and honey can be converted into realistic blocks
- Realistic ice toggle for vanilla ice
- Realistic ice for realistic water (can be obtained if the water is freezing)
- Realistic slush for realistic water (can be obtained if the water is almost freezing)
- Realistic things react to TNT (you can grab a realistic TNT and throw it)
- Realistic rain that the realistic water reacts to and the rain feeds the water (makes steam in lava)
- Realistic Water Drain (drins realistic water)
- Sodium menu support
- Clip rendering and packing it into a mp4 with ffmpeg (the mod doesnt contain ffmpeg, you have to download it manually though you only need this if you wanna render clips inside the mod. you can just use obs)
- Water pumps, moves the water from its input to its output when powered by redstone (you can change the speed of it by right clicking on it) (realistic pump works in realistic ocean)
- Water starts disappearing after being boiled for too long on too high temperature
- Water makes steam, boiling sounds, boiling particles on high temperature
- Water heats up by being too close to hot things like Magma Block, Campfire, Lava, Fire
- Too hot water damages you if you are inside
- Water cools down by having cold blocks around it like Ice, Snow Block, Snow or being in cold biomes. Putting more water in hot water will just make warm water which then cools down to the biome's temperature
- Realistic things like water, lava can go through any portals
- Vitrail shader loader support (Vitrail can load in any OF/Iris shader on Vulkan)
- Realistic Ocean, changes how the oceans render plus it reacts to entities/realistic items from this mod
- Realistic fire (see the Fire section below)
- Realistic kelp, its a toggle you can turn on/off in "/water" it lets you grab a kelp, move it, and it will react to things around it

### Fire

Fire in this mod is drawn on the GPU with the same Vulkan particle system the steam uses, so it needs the Vulkan graphics API like the water does. If you run on OpenGL it falls back to ordinary Minecraft particles.

**What it looks like:** flames are licks of fire that stretch the way they're moving, with a hot yellow-white core fading to orange and red. Embers drift up and cool off, and the smoke picks up an orange glow while it's still close to the flames. A bigger blaze gets bigger, hotter flames. Fire sticks to the sides of the blocks that are burning instead of floating in the air, and it's drawn after mobs, so you can't see them through it. Soul fire burns blue.

**What counts as fire:** the realistic blocks from the creative inventory. That's realistic fire and soul fire, realistic flint and steel and fire charges, torches (normal, soul, copper and redstone, including the wall ones), campfires, furnaces, blast furnaces and smokers, and all 17 candle colours. Realistic water and lava always count. Plain vanilla fire, torches and so on only get the effect if you turn on "Count vanilla fire blocks" in the Fire settings (it's off by default so the mod doesn't touch things it didn't add).

**How it spreads:** realistic fire eats the fuel next to it a little at a time, so a log smoulders while dry leaves go up fast. Heat rises, so fire climbs far quicker than it spreads sideways or down. The wind pushes it downwind, and every flame nearby makes it hotter, so a fire that gets going keeps feeding itself. Nothing catches instantly: fire soaks heat into the fuel next to it a little at a time, and small flames start on the side of the block nearest the fire and creep across its face as it heats up, until the block finally ignites. If the fire dies down first, the block cools off again. With nothing left to burn it dies down and leaves a ragged patch of coarse dirt where grass, podzol or mycelium used to be (only on open ground, never under a roof). Rain puts it out slowly. Fire sitting on netherrack and other always-burning ground never goes out.

**Water and fire:** realistic water next to realistic fire puts it out. It doesn't have to touch a face, a flame on a corner of the water counts as well. The same goes the other way: fire on any edge or corner of a water block heats it, so a pool surrounded by flames will boil. Heat also travels through metal: iron, copper (every variant) and gold blocks next to fire, lava, magma or a lit furnace pass the heat on to water touching them, and through a few blocks of connected metal, so a line of copper blocks over a campfire can boil a pot of water.

**Lighting it:** use the realistic flint and steel or realistic fire charge, either by hand or from a dispenser. A dispenser puts fire in the block in front of it. If it can't, it does what a vanilla dispenser would (lights a campfire, candle or TNT, or shoots a fireball).

**Commands:**

- "/fire stats" shows how many fire sources are being drawn and how many particles were spawned
- "/fire list" lists the first few (give it a number for more)
- "/fire rescan" makes the mod look for fire blocks around you again
- "/water remove fire" (op only) deletes realistic fire blocks around you (up to 400 per run, so run it again for a big one) and clears the flames and smoke

**Settings:** everything is in the "Fire" section of "/water gui", and each one can also be changed with "/water set". You can turn fire off completely, change how fast it spreads (a server-wide multiplier that only operators can change), set how many sources are drawn and how far away, and change the flame size, lifetime, rise speed, brightness and opacity. Smoke, sparks and steam each have their own controls too. If fire is costing too many frames, lower the max sources or the particle budget first.

**Showcase:** https://www.youtube.com/watch?v=GG2-psEHUI4 (this is the old one, new is coming soon)

#

**Realistic Blocks:**

![Project Screenshot](images/3.png)

**Realistic Water flowing:**

![Project Screenshot](images/4.png)

**Wobbly Slime and Honey:**

![Project Screenshot](images/5.png)

Credits: https://github.com/claude, https://github.com/eva-akselrad, https://www.youtube.com/@axocali