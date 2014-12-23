#!/bin/bash

# Build application and distribute over cluster hosts
#
#

# Remove old application
rm application.jar;
# Generate jpregel dist
ant clean;
ant compile;
ant build_jpregel;
# Compiling new application package
javac -cp dist/jpregel.jar PageRankVertex.java;
jar -cvf application.jar PageRankVertex.class;
rm PageRankVertex.class

# Deploy into cluster
scp -r * mtrotti@host1:/home/mtrotti/jpregel/ ;
scp -r * mtrotti@host2:/home/mtrotti/jpregel/ ;
