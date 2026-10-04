"""
Export the pretrained UVDoc model (MIT, https://github.com/tanguymagne/UVDoc) to ONNX
for on-device inference with ONNX Runtime, and check the export against PyTorch.

Usage:
    git clone https://github.com/tanguymagne/UVDoc
    python tools/export_uvdoc_onnx.py --uvdoc path/to/UVDoc \
        --out app/src/main/assets/uvdoc.onnx

Model contract (what the app relies on):
    input  "image":  float32 [1, 3, 712, 488]  RGB in 0..1, image resized to 488x712 (w x h)
    output "grid":   float32 [1, 2, 45, 31]    for each point of a 31x45 grid over the flat page,
                     where it lies in the input photo, as (x, y) in -1..1 (align_corners=True)
"""

import argparse
import sys

import numpy as np
import torch


class GridOnly(torch.nn.Module):
    """UVDoc returns (2D grid, 3D grid); only the 2D grid is needed to unwarp."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, image):
        grid2d, _ = self.model(image)
        return grid2d


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--uvdoc", required=True, help="path to a clone of the UVDoc repository")
    parser.add_argument("--out", default="app/src/main/assets/uvdoc.onnx")
    args = parser.parse_args()

    sys.path.insert(0, args.uvdoc)
    from model import UVDocnet  # noqa: E402

    model = UVDocnet(num_filter=32, kernel_size=5)
    ckpt = torch.load(f"{args.uvdoc}/model/best_model.pkl", map_location="cpu", weights_only=False)
    model.load_state_dict(ckpt["model_state"])
    model.eval()
    wrapped = GridOnly(model).eval()

    dummy = torch.rand(1, 3, 712, 488)
    torch.onnx.export(
        wrapped,
        dummy,
        args.out,
        input_names=["image"],
        output_names=["grid"],
        opset_version=17,
        dynamo=False,
    )

    import onnxruntime as ort

    session = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"])
    for seed in range(3):
        torch.manual_seed(seed)
        x = torch.rand(1, 3, 712, 488)
        with torch.no_grad():
            expected = wrapped(x).numpy()
        actual = session.run(["grid"], {"image": x.numpy()})[0]
        diff = float(np.abs(expected - actual).max())
        print(f"seed {seed}: output {actual.shape}, max |torch - onnx| = {diff:.2e}")
        assert diff < 1e-3, "ONNX output does not match PyTorch"
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
