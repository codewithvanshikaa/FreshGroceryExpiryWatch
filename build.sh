#!/usr/bin/env bash
mkdir -p out
javac -d out $(find . -name "*.java") && echo "Build done"
