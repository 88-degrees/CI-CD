package io.onedev.server.manager;

import io.onedev.commons.utils.FileUtils;

import java.io.File;

public interface StorageManager {
	
	File initLfsDir(Long projectId);

	File initArtifactsDir(Long projectId, Long buildNumber);
	
}
