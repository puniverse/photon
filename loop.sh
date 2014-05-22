#!/bin/sh
echo > res.log
for i in `seq 1 500`
do
	wget -t 1 --timeout=8 -q -O - http://localhost:8080/fiber\?sleep\=5000 >> res.log &
done
