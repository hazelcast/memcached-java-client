#!/bin/bash

#echo "Value of 1: $1"

for((i=0; i<$1; i++))
do
	echo "sleeping $i"
	echo " $i ***********************"
	nohup java -Xms4g -Xmx4g -classpath ./target/classes:/Users/rahul/Hazelcast/hazelcast-distributions/hazelcast-enterprise-3.7.5/lib/hazelcast-enterprise-all-3.7.5.jar org.hazelcast.client.MemcachedMainClient > client."$i".log &
	sleep 1

done
