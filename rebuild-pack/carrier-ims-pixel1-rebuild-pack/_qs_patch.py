#!/usr/bin/env python3
# Remove SIM2VoLTETileService + SIM2IMSStatusTileService from QsTiles.kt
# Pixel 1 is single-SIM; SIM2 tiles are unnecessary.
import io

path = "/tmp/carrier-ims/app/src/main/java/io/github/vvb2060/ims/tiles/QsTiles.kt"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

# Block 1: SIM2VoLTETileService (with one trailing blank line)
sim2_volte = """class SIM2VoLTETileService : BaseVoLTETileService() {
    override val simSlotIndex: Int = 1
}

"""
assert src.count(sim2_volte) == 1, "SIM2VoLTETileService block not found exactly once"
src = src.replace(sim2_volte, "")

# Block 2: SIM2IMSStatusTileService (trailing file end)
sim2_ims = """class SIM2IMSStatusTileService : BaseIMSStatusTileService() {
    override val simSlotIndex: Int = 1
}
"""
assert src.count(sim2_ims) == 1, "SIM2IMSStatusTileService block not found exactly once"
src = src.replace(sim2_ims, "").rstrip() + "\n"

with io.open(path, "w", encoding="utf-8") as f:
    f.write(src)

print("OK QsTiles.kt patched, removed SIM2VoLTETileService + SIM2IMSStatusTileService")
