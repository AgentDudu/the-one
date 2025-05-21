#! /bin/sh
java -Xmx4G -Djava.util.Arrays.useLegacyMergeSort=true -cp target:lib/ECLA.jar:lib/DTNConsoleConnection.jar core.DTNSim $*
