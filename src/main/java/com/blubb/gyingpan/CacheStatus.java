package com.blubb.gyingpan;

import java.io.Serializable;

public enum CacheStatus implements Serializable {
	NotInCache, Downloading, InCache, Dirty
}
