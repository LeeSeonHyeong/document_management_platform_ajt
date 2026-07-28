package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AsyncDocumentParseJobLauncher implements DocumentParseJobLauncher {

    private final ExecutorService documentParseExecutor;
    private final DocumentParseWorker worker;

    public AsyncDocumentParseJobLauncher(
            ExecutorService documentParseExecutor,
            DocumentParseWorker worker
    ) {
        this.documentParseExecutor = documentParseExecutor;
        this.worker = worker;
    }

    @Override
    public void launch(AiJob job) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(job);
                }
            });
            return;
        }
        submit(job);
    }

    private void submit(AiJob job) {
        documentParseExecutor.submit(() -> worker.parse(job));
    }
}
