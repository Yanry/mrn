# MRN Design Compiler synthesis

`scripts_mrn/run_dc.sh` synthesizes the SpinalHDL `MergeReductionTree` top.
The generator currently uses 16 lanes, FIFO depth 8 and 8-bit row/column
coordinates. It produces one Verilog file containing the entire hierarchy.

## Generate RTL

From the project root:

```bash
sbt 'runMain GenerateMergeReductionTree'
```

This writes `rtl/MergeReductionTree.v`. Change the parameters in
`src/main/scala/mrn/MergeReductionTree.scala` and regenerate RTL to synthesize
a different configuration. `TOP_NAME` selects the Verilog module name, not
the number of lanes.

## Run synthesis

Load the Synopsys DC environment and run:

```bash
scripts_mrn/run_dc.sh
```

The bundled `synopsys_dc.setup` is unchanged from the original script. It
contains fixed technology library and DesignWare paths; these must exist in
the DC environment. To use a different technology setup without editing it:

```bash
DC_SETUP_FILE=/absolute/path/to/your_dc_setup.tcl scripts_mrn/run_dc.sh
```

The script can be launched from another directory. The default output is
`target/dc_mrn/output/` and the DC design library is `target/dc_mrn/work/`;
both are ignored by Git. Output includes `MergeReductionTree_mapped.v`,
`MergeReductionTree_post.ddc`, `MergeReductionTree.sdc`, an SVF, and timing,
area, power, QoR, constraints and design-check reports.

Optional overrides:

- `CLOCK_PERIOD_NS`: clock period in ns (default `2.0`).
- `RTL_FILE`: generated `.v` or `.sv` top file (default `rtl/MergeReductionTree.v`).
- `TOP_NAME`: module to elaborate (default `MergeReductionTree`).
- `OUTPUT_ROOT`: DC working/output directory (default `target/dc_mrn`).
- `DC_SETUP_FILE`: alternative DC technology setup (default bundled
  `synopsys_dc.setup`).

The bundled constraints target top-level `clk` and active-high `reset` and
apply input/output delays to the streaming interface. Adjust
`DefaultConstrain.tcl` to match your actual system timing budget and loads.
If `dc_shell` or the technology library is unavailable, synthesis cannot run.
