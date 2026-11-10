"""Stores an ONNX model's 32-bit float weights as 16-bit floats, roughly halving the file.

The model still computes in 32-bit: each stored weight gets a Cast node that widens it when
the model is loaded, so every runtime and accelerator that ran the original runs this one.
Only the weights' precision changes (about 3 significant digits are kept).

    python tools/halve_model.py in.onnx out.onnx
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

MIN_ELEMENTS = 256  # smaller tensors (shapes, biases of tiny layers) aren't worth a node


def halve(src: str, dst: str) -> None:
    model = onnx.load(src)
    graph = model.graph
    casts = []
    kept = []
    for init in graph.initializer:
        if init.data_type != TensorProto.FLOAT or np.prod(init.dims) < MIN_ELEMENTS:
            kept.append(init)
            continue
        values = numpy_helper.to_array(init)
        half = values.astype(np.float16)
        if not np.all(np.isfinite(half)):
            kept.append(init)  # a weight too large for 16 bits: leave this tensor alone
            continue
        stored = init.name + "_f16"
        kept.append(numpy_helper.from_array(half, stored))
        casts.append(helper.make_node("Cast", [stored], [init.name], to=TensorProto.FLOAT, name=init.name + "_widen"))
    del graph.initializer[:]
    graph.initializer.extend(kept)
    nodes = list(graph.node)
    del graph.node[:]
    graph.node.extend(casts + nodes)
    # Initializers listed as graph inputs (old exporters do this) would now be dangling.
    names = {i.name for i in graph.initializer}
    widened = {c.output[0] for c in casts}
    inputs = [i for i in graph.input if i.name not in widened or i.name in names]
    del graph.input[:]
    graph.input.extend(inputs)
    onnx.checker.check_model(model)
    onnx.save(model, dst)
    print(f"{len(casts)} tensors stored as 16-bit")


if __name__ == "__main__":
    halve(sys.argv[1], sys.argv[2])
