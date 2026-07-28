package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;

public interface DocumentParseJobLauncher {

    void launch(AiJob job);
}
