package io.github.ogshrug.flatdocscanner.scan

import java.nio.FloatBuffer

/**
 * One loaded neural network. The app has two builds that differ only in what runs these:
 * ONNX Runtime (flavour `onnx`) or Ferrite (flavour `ferrite`); see ModelEngine in each
 * flavour's source set. Both take the same float32 NCHW input and fill the same output.
 */
interface ModelRunner {
    /**
     * Runs the model on [input] (shape [inShape], read from its position 0) and writes the
     * single output, of shape [outShape], into [output]. Not safe to call from two threads at
     * once on the same runner; callers serialise.
     */
    fun run(input: FloatBuffer, inShape: IntArray, output: FloatArray, outShape: IntArray)
}
