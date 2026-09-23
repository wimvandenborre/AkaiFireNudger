#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mvn -o clean package
check_classpath="target/yaeltexarpcontrol-0.1.jar:target/test-classes:${HOME}/.m2/repository/com/bitwig/extension-api/25/extension-api-25.jar"
for check in DrumPadActivityChecks OledMidiChecks EuclideanPatternChecks EuclideanRotationChecks GrooveFixtureChecks GrooveLogicVelocityChecks GrooveVelocityPreferencesChecks GrooveSessionChecks GrooveHostChecks GrooveBatchChecks GrooveLockChecks GrooveLatencyChecks GrooveWorkerChecks FineNudgeChecks MulticlipTargetChecks NoteSnapshotChecks StepViewPositionChecks StepHoldGestureChecks FineNudgeControllerChecks FineGridChecks LogicalStepIndexChecks; do
    java -cp "$check_classpath" "com.akai.fire.sequence.$check"
done
cp target/yaeltexarpcontrol-0.1.jar target/FireNudger.bwextension
