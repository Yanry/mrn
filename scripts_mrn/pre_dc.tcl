# Synopsys Design Compiler flow for the SpinalHDL MergeReductionTree top.

set SCRIPT_DIR [file dirname [file normalize [info script]]]
set REPO_ROOT [file dirname $SCRIPT_DIR]

proc env_or_default {name default_value} {
    if {[info exists ::env($name)] && $::env($name) ne ""} {
        return $::env($name)
    }
    return $default_value
}

set DC_SETUP_FILE [file normalize [env_or_default DC_SETUP_FILE \
    [file join $SCRIPT_DIR synopsys_dc.setup]]]
if {![file isfile $DC_SETUP_FILE]} {
    error "DC_SETUP_FILE does not exist: $DC_SETUP_FILE"
}
source -echo -verbose $DC_SETUP_FILE

set TOP_NAME [env_or_default TOP_NAME "MergeReductionTree"]
set RTL_FILE [file normalize [env_or_default RTL_FILE \
    [file join $REPO_ROOT rtl MergeReductionTree.v]]]
set CLOCK_PERIOD_NS [env_or_default CLOCK_PERIOD_NS "2.0"]
if {![string is double -strict $CLOCK_PERIOD_NS] || $CLOCK_PERIOD_NS <= 0} {
    error "CLOCK_PERIOD_NS must be a positive number: $CLOCK_PERIOD_NS"
}
set OUTPUT_ROOT [file normalize [env_or_default OUTPUT_ROOT \
    [file join $REPO_ROOT target dc_mrn]]]
set DESIGN_LIB [file join $OUTPUT_ROOT work]
set OUTPUT_DIR [file join $OUTPUT_ROOT output]

if {![file isfile $RTL_FILE]} {
    error "RTL_FILE does not exist: $RTL_FILE"
}
set RTL_FORMAT [string tolower [file extension $RTL_FILE]]
if {$RTL_FORMAT ne ".v" && $RTL_FORMAT ne ".sv"} {
    error "RTL_FILE must be a Verilog or SystemVerilog file: $RTL_FILE"
}

file mkdir $DESIGN_LIB $OUTPUT_DIR
define_design_lib MY_LIB -path $DESIGN_LIB

set_svf [file join $OUTPUT_DIR "${TOP_NAME}.svf"]
saif_map -start

puts "INFO: top          = $TOP_NAME"
puts "INFO: RTL          = $RTL_FILE"
puts "INFO: clock period = ${CLOCK_PERIOD_NS} ns"
puts "INFO: output       = $OUTPUT_DIR"

# SpinalHDL emits the complete hierarchy into a single generated file.
if {$RTL_FORMAT eq ".sv"} {
    analyze -format sverilog $RTL_FILE -library MY_LIB
} else {
    analyze -format verilog $RTL_FILE -library MY_LIB
}
elaborate $TOP_NAME -library MY_LIB
current_design $TOP_NAME
link

source -echo -verbose [file join $SCRIPT_DIR DefaultConstrain.tcl]
check_design > [file join $OUTPUT_DIR "${TOP_NAME}_check_design.rpt"]

compile_ultra

change_names -rules verilog -hierarchy
write -hierarchy -format ddc \
    -output [file join $OUTPUT_DIR "${TOP_NAME}_post.ddc"]
write -hierarchy -format verilog \
    -output [file join $OUTPUT_DIR "${TOP_NAME}_mapped.v"]
write_sdc [file join $OUTPUT_DIR "${TOP_NAME}.sdc"]

source -echo -verbose [file join $SCRIPT_DIR report.tcl]

puts "INFO: $TOP_NAME synthesis completed successfully"
