#!/usr/bin/env python3
"""Writes the Shader Hand post effects: hand_<look 0-1>_<frame 00-23>.json (the frame is the animation phase).
Run from the project root before building: python3 tools/gen_hand_effects.py"""
import json, os

out = "src/main/resources/assets/blockoutlines/post_effect"
os.makedirs(out, exist_ok=True)

for look in range(2):
    for frame in range(24):
        effect = {
            "targets": {"swap": {}},
            "passes": [
                {
                    "vertex_shader": "minecraft:core/screenquad",
                    "fragment_shader": "blockoutlines:post/hand",
                    "inputs": [
                        {"sampler_name": "In", "target": "minecraft:main"},
                        {"sampler_name": "Depth", "target": "minecraft:main", "use_depth_buffer": True},
                    ],
                    "output": "swap",
                    "uniforms": {"HandConfig": [{"name": "Params", "type": "vec4", "value": [frame / 24.0, float(look), 0.0, 0.0]}]},
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
        with open(os.path.join(out, "hand_%d_%02d.json" % (look, frame)), "w") as f:
            json.dump(effect, f, indent=2)
