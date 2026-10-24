#!/usr/bin/env python3
"""Writes the wet-world post effects used by Weather Plus: one json per camera pitch / fov / strength step.
The shader needs the camera pitch and fov, and a post effect can only hold fixed numbers, so there is one per step.
Run from the project root before building: python3 tools/gen_wet_effects.py"""
import json, math, os

out = "src/main/resources/assets/blockoutlines/post_effect"
os.makedirs(out, exist_ok=True)

FOVS = [70, 90, 110]
STRENGTH = [0.55, 1.0]

for pi in range(10):                 # pitch 0 .. 90 degrees, step 10
    for fi, fov in enumerate(FOVS):
        for si, st in enumerate(STRENGTH):
            params = [math.radians(pi * 10), math.tan(math.radians(fov) / 2), st, 0.0]
            effect = {
                "targets": {"swap": {}},
                "passes": [
                    {
                        "vertex_shader": "minecraft:core/screenquad",
                        "fragment_shader": "blockoutlines:post/wet",
                        "inputs": [
                            {"sampler_name": "In", "target": "minecraft:main"},
                            {"sampler_name": "Depth", "target": "minecraft:main", "use_depth_buffer": True},
                        ],
                        "output": "swap",
                        "uniforms": {"WetConfig": [{"name": "Params", "type": "vec4", "value": params}]},
                    },
                    {
                        "vertex_shader": "minecraft:core/screenquad",
                        "fragment_shader": "minecraft:post/blit",
                        "inputs": [{"sampler_name": "In", "target": "swap"}],
                        "output": "minecraft:main",
                        "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
                    },
                ],
            }
            with open(os.path.join(out, "wet_%02d_%d_%d.json" % (pi, fi, si)), "w") as f:
                json.dump(effect, f, indent=2)
