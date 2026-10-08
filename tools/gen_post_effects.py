#!/usr/bin/env python3
"""Writes the color grading post effects (one json per saturation/contrast/brightness step).
Run from the project root before building: python3 tools/gen_post_effects.py"""
import json, os

out = "src/main/resources/assets/blockoutlines/post_effect"
os.makedirs(out, exist_ok=True)

for s in range(13):          # saturation 0.00 .. 3.00 step 0.25
    for c in range(5):       # contrast   0.50 .. 1.50 step 0.25
        for b in range(5):   # brightness -0.2 .. 0.2  step 0.1
            params = [s * 0.25, 0.5 + c * 0.25, -0.2 + b * 0.1, 0.0]
            effect = {
                "targets": {"swap": {}},
                "passes": [
                    {
                        "vertex_shader": "minecraft:core/screenquad",
                        "fragment_shader": "blockoutlines:post/grade",
                        "inputs": [{"sampler_name": "In", "target": "minecraft:main"}],
                        "output": "swap",
                        "uniforms": {"GradeConfig": [{"name": "Params", "type": "vec4", "value": params}]},
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
            with open(os.path.join(out, "grade_%02d_%d_%d.json" % (s, c, b)), "w") as f:
                json.dump(effect, f, indent=2)
