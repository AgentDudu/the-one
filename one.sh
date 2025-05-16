#! /bin/sh
java -Xmx2G -Djava.util.Arrays.useLegacyMergeSort=true -cp target:lib/ECLA.jar:lib/DTNConsoleConnection.jar core.DTNSim $*
