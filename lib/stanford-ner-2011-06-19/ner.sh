#!/bin/sh
java -mx700m -cp "stanford-ner.jar:" edu.stanford.nlp.ie.crf.CRFClassifier -loadClassifier classifiers/all.3class.distsim.crf.ser.gz -textFile $1
