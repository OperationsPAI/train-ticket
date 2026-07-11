#!/bin/sh
PROCS=${LOADGEN_PROCESSES:-4}
echo "[launcher] starting $PROCS loadgen processes"
for i in $(seq 1 $PROCS); do
  python /app/loadgen.py &
done
wait
