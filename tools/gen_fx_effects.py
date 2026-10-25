#!/usr/bin/env python3
"""Writes the shared bloom / shock-wave post effects (Loot Beams and Kill Effect).
fx_<bloom 0-2>_<shock frame 0-9>_<shock strength 0-1>.json
Run from the project root before building: python3 tools/gen_fx_effects.py"""
import json, os

out = "src/main/resources/assets/blockoutlines/post_effect"
os.makedirs(out, exist_ok=True)

BLOOM = [0.0, 0.55, 1.0]
SHOCK_STRENGTH = [0.55, 1.0]

for b in range(3):
    for f in range(10):
        for s in range(2):
            if f == 0 and s == 1:
                continue
            if b == 0 and f == 0:
                continue
            params = [BLOOM[b], f / 9.0 if f > 0 else 0.0, SHOCK_STRENGTH[s] if f > 0 else 0.0, 0.0]
            if f == 9:
                params[1] = 0.999
            effect = {
                "targets": {"swap": {}},
                "passes": [
                    {
                        "vertex_shader": "minecraft:core/screenquad",
                        "fragment_shader": "blockoutlines:post/fx",
                        "inputs": [{"sampler_name": "In", "target": "minecraft:main"}],
                        "output": "swap",
                        "uniforms": {"FxConfig": [{"name": "Params", "type": "vec4", "value": params}]},
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
            with open(os.path.join(out, "fx_%d_%d_%d.json" % (b, f, s)), "w") as fh:
                json.dump(effect, fh, indent=2)
