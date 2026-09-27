"""Write what the desktop dog-vision computes, as the reference core's tests compare against.

The desktop dog-vision is https://github.com/loplex/dog-vision-python. From a checkout of it, run

    uv run --project <checkout> python tools/reference_values.py core/src/jvmTest/resources

It writes three files there, each line tab-separated:

- reference.tsv: a kind of value, the species and the chroma scale it is for, and the values.
- images.tsv: a case of the image pipeline, the width and height of its result, and the result's
  pixels, row by row, each as R, G and B. The input is pattern(), which the tests draw alike.
- facts.tsv: a species, the English label of one of its facts, and the pieces of its value.
"""

import sys
from pathlib import Path

import numpy as np

from dog_vision.core.facts import species_facts
from dog_vision.core.imaging import compose
from dog_vision.core.model import CHROMA_SCALES, Params, neutral_point, rnl_gains, simulation_matrix
from dog_vision.core.species import SPECIES

SCENE_MEAN = np.array([0.8, 0.3, 0.1])  # a warm scene, for the adaptation


def row(kind: str, species: str, scale: str, values) -> str:
    return "\t".join([kind, species, scale, *(repr(float(v)) for v in np.ravel(values))])


def pattern(width: int = 40, height: int = 30) -> np.ndarray:
    """A BGR image in which neighbouring pixels differ in every channel."""
    y, x = np.mgrid[0:height, 0:width]
    rgb = np.stack([(x * 37 + y * 11) % 256, (x * 13 + y * 71) % 256, (x * 101 + y * 29) % 256], axis=-1)
    return rgb[..., ::-1].astype(np.uint8)


# Each case: its name, and compose()'s arguments after the frame.
IMAGE_CASES = {
    "dog": (Params("dog"), False, None, False),
    "dog-rnl-adapted": (Params("dog", adaptation=0.5, strength=0.75, chroma_scale="rnl"), False, None, False),
    "dog-acuity": (Params("dog", acuity=True, field_of_view=1), False, None, False),
    "cow-acuity": (Params("cow", acuity=True, field_of_view=2), False, None, False),
    "seal": (Params("harbour-seal"), False, None, False),
    "original-and-horse": (Params("horse"), True, None, False),
    "deuteranope-dog-difference": (Params("dog"), True, "deuteranope", True),
}

out = Path(sys.argv[1])
with open(out / "reference.tsv", "w") as reference:
    for species in SPECIES:
        for scale in CHROMA_SCALES:
            print(row("simulation", species, scale, simulation_matrix(Params(species, chroma_scale=scale))), file=reference)
            adapted = Params(species, chroma_scale=scale, adaptation=0.5, strength=0.75)
            print(row("adapted", species, scale, simulation_matrix(adapted, SCENE_MEAN)), file=reference)
        print(row("rnl-gains", species, "-", rnl_gains(Params(species))), file=reference)
        if len(SPECIES[species]) == 2:
            print(row("neutral-point", species, "-", [neutral_point(Params(species))]), file=reference)
with open(out / "images.tsv", "w") as images:
    for name, arguments in IMAGE_CASES.items():
        image, share = compose(pattern(), *arguments)
        rgb = image[..., ::-1]
        fields = [name, str(rgb.shape[1]), str(rgb.shape[0]), repr(share), *map(str, rgb.ravel())]
        print("\t".join(fields), file=images)
with open(out / "facts.tsv", "w") as facts:
    for species in SPECIES:
        for label, value, _description in species_facts(species):
            print("\t".join([species, label, *value]), file=facts)
