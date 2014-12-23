#!/bin/bash

# Run JPregel Master
ant runRemoteMaster -Dusername=mtrotti -Dapp=/home/mtrotti/jpregel/application.jar -Dvertex_class=PageRankVertex -Dnum_machines=1;
