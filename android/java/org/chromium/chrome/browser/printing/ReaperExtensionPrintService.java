// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.printing;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintJobId;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;

import org.json.JSONArray;
import org.json.JSONObject;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.firstrun.FirstRunStatus;
import org.chromium.chrome.browser.init.ChromeBrowserInitializer;
import org.chromium.chrome.browser.init.EmptyBrowserParts;
import org.chromium.chrome.browser.profiles.ProfileManager;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** System-bound, user-enabled Android print destination for PDF/PWG-capable MV3 providers. */
public final class ReaperExtensionPrintService extends PrintService
        implements ReaperExtensionPrinterBridge.Listener {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Request> mRequests = new HashMap<>();
    private final Map<PrintJobId, PrintJob> mJobs = new HashMap<>();
    private final Set<Discovery> mSessions = new HashSet<>();
    private final ArrayDeque<Runnable> mQueryQueue = new ArrayDeque<>();
    private ReaperExtensionPrinterBridge mBridge;
    private boolean mConnected;
    private boolean mStarting;
    private boolean mDestroyed;
    private int mStartupGeneration;
    private int mNextRequest;
    private int mQueriesInFlight;

    private abstract class Request {
        final int id = nextRequestId();
        Discovery discovery;
        PrintJob job;
        boolean querying;
        final Runnable timeout = () -> {
            if (finish(id) == null) return;
            if (mBridge != null) mBridge.cancel(id);
            failed();
        };
        void printers(JSONArray unusedPrinters) throws Exception {}
        void capabilities(String unusedJson) throws Exception {}
        void printed(String unusedError) {}
        void failed() { if (job != null) failJob(job); }
    }

    private int nextRequestId() {
        if (mNextRequest == Integer.MAX_VALUE) {
            throw new IllegalStateException("Printer request identifier exhausted");
        }
        return ++mNextRequest;
    }

    private void query(Request request, Runnable send) {
        mRequests.put(request.id, request);
        mQueryQueue.add(() -> {
            if (!mRequests.containsKey(request.id) || mBridge == null) return;
            request.querying = true;
            mQueriesInFlight++;
            mHandler.postDelayed(request.timeout, 30_000);
            try { send.run(); }
            catch (RuntimeException exception) {
                finish(request.id);
                if (mBridge != null) mBridge.cancel(request.id);
                request.failed();
            }
        });
        pumpQueries();
    }

    private void pumpQueries() {
        while (mBridge != null && mQueriesInFlight < 8 && !mQueryQueue.isEmpty()) {
            mQueryQueue.remove().run();
        }
    }

    private Request finish(int id) {
        Request request = mRequests.remove(id);
        if (request != null) {
            mHandler.removeCallbacks(request.timeout);
            if (request.querying) mQueriesInFlight--;
            // Avoid reentrant native dispatch from an extension response callback.
            mHandler.post(this::pumpQueries);
        }
        return request;
    }

    @Override
    protected void onConnected() {
        mConnected = true;
        ensureBrowser();
    }

    private void ensureBrowser() {
        if (mBridge != null || mStarting || !mConnected || mDestroyed) return;
        if (!FirstRunStatus.getFirstRunFlowComplete()) {
            failAllJobs();
            return;
        }
        mStarting = true;
        int generation = ++mStartupGeneration;
        EmptyBrowserParts parts = new EmptyBrowserParts() {
            @Override
            public boolean isActivityFinishingOrDestroyed() {
                return mDestroyed || !mConnected || generation != mStartupGeneration;
            }
            @Override
            public void finishNativeInitialization() {
                if (isActivityFinishingOrDestroyed()) return;
                mStarting = false;
                try {
                    mBridge = new ReaperExtensionPrinterBridge(
                            ProfileManager.getLastUsedRegularProfile(),
                            ReaperExtensionPrintService.this);
                } catch (RuntimeException exception) {
                    failAllJobs();
                    return;
                }
                for (Discovery session : new ArrayList<>(mSessions)) session.discover();
                for (PrintJob job : new ArrayList<>(mJobs.values())) dispatchJob(job);
            }
            @Override
            public void onStartupFailure(Exception failure) {
                if (generation != mStartupGeneration) return;
                mStarting = false;
                failAllJobs();
            }
        };
        try {
            ChromeBrowserInitializer.getInstance().handlePreNativeStartupAndLoadLibraries(parts);
            ChromeBrowserInitializer.getInstance().handlePostNativeStartup(true, parts);
        } catch (RuntimeException exception) {
            mStarting = false;
            failAllJobs();
        }
    }

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        Discovery session = new Discovery();
        mSessions.add(session);
        return session;
    }

    private final class Discovery extends PrinterDiscoverySession {
        private final Map<PrinterId, String> mNames = new HashMap<>();
        private boolean mDiscovering;
        private boolean mDead;

        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            mDiscovering = true;
            ensureBrowser();
            discover();
        }

        void discover() {
            if (mDead || !mDiscovering || mBridge == null) return;
            cancelRequests();
            removePrinters(new ArrayList<>(mNames.keySet()));
            mNames.clear();
            Request request = new Request() {
                @Override
                void printers(JSONArray printers) throws Exception {
                    if (mDead || !mDiscovering) return;
                    for (int i = 0; i < printers.length(); i++) {
                        JSONObject printer = printers.getJSONObject(i);
                        String localId = printer.getString("id");
                        String name = printer.getString("name");
                        if (!localId.contains(":") || localId.length() > 4096 || name.isEmpty()) continue;
                        PrinterId id = generatePrinterId(localId);
                        String label = name + " (" + printer.optString("extensionName", "Extension") + ")";
                        if (mNames.putIfAbsent(id, label) == null) validate(id);
                    }
                }
            };
            request.discovery = this;
            query(request, () -> mBridge.getPrinters(request.id));
        }

        private void validate(PrinterId printerId) {
            if (mDead || mBridge == null || !mNames.containsKey(printerId)) return;
            Request request = new Request() {
                @Override
                void capabilities(String json) throws Exception {
                    if (mDead) return;
                    ReaperExtensionPrinterCapabilities capabilities =
                            ReaperExtensionPrinterCapabilities.parse(printerId, json);
                    if (capabilities == null) {
                        removePrinters(Collections.singletonList(printerId));
                        return;
                    }
                    PrinterInfo printer = new PrinterInfo.Builder(
                            printerId, mNames.get(printerId), PrinterInfo.STATUS_IDLE)
                            .setCapabilities(capabilities.getInfo()).build();
                    addPrinters(Collections.singletonList(printer));
                }
                @Override
                void failed() {
                    if (!mDead) removePrinters(Collections.singletonList(printerId));
                }
            };
            request.discovery = this;
            query(request, () -> mBridge.getCapabilities(request.id, printerId.getLocalId()));
        }

        private void cancelRequests() {
            for (Request request : new ArrayList<>(mRequests.values())) {
                if (request.discovery != this) continue;
                finish(request.id);
                if (mBridge != null) mBridge.cancel(request.id);
            }
        }

        @Override
        public void onStopPrinterDiscovery() {
            mDiscovering = false;
            cancelRequests();
        }
        @Override
        public void onValidatePrinters(List<PrinterId> ids) {
            for (PrinterId id : ids) validate(id);
        }
        @Override
        public void onStartPrinterStateTracking(PrinterId id) { validate(id); }
        @Override
        public void onStopPrinterStateTracking(PrinterId id) {}
        @Override
        public void onDestroy() {
            mDead = true;
            cancelRequests();
            mSessions.remove(this);
        }
    }

    @Override
    protected void onPrintJobQueued(PrintJob job) {
        mJobs.put(job.getId(), job);
        ensureBrowser();
        if (mBridge != null) dispatchJob(job);
    }

    private void dispatchJob(PrintJob job) {
        if (mBridge == null || !mJobs.containsKey(job.getId()) || !job.isQueued()) return;
        PrinterId printerId = job.getInfo().getPrinterId();
        if (printerId == null || !printerId.equals(generatePrinterId(printerId.getLocalId()))
                || !job.start()) {
            failJob(job);
            return;
        }
        Request request = new Request() {
            @Override
            void capabilities(String json) throws Exception {
                ReaperExtensionPrinterCapabilities capabilities =
                        ReaperExtensionPrinterCapabilities.parse(printerId, json);
                if (capabilities == null) { failJob(job); return; }
                sendPdf(job, printerId, capabilities.buildTicket(job.getInfo()));
            }
        };
        request.job = job;
        query(request, () -> mBridge.getCapabilities(request.id, printerId.getLocalId()));
    }

    private void sendPdf(PrintJob job, PrinterId printerId, String ticket) throws IOException {
        if (!mJobs.containsKey(job.getId()) || job.isCancelled() || mBridge == null) return;
        Request request = new Request() {
            @Override
            void printed(String error) {
                if (mJobs.remove(job.getId()) == null) return;
                if (error.isEmpty()) job.complete();
                else job.fail(getString(R.string.reaper_extension_print_failed));
            }
        };
        request.job = job;
        mRequests.put(request.id, request);
        mHandler.postDelayed(request.timeout, 125_000);
        try (ParcelFileDescriptor document = job.getDocument().getData()) {
            if (document == null) throw new IOException("Print spooler did not supply a PDF");
            // JNI duplicates the borrowed fd before returning; Java always owns this handle.
            mBridge.print(request.id, printerId.getLocalId(), job.getInfo().getLabel(), ticket,
                    job.getInfo().getAttributes().getMediaSize().getWidthMils(),
                    job.getInfo().getAttributes().getMediaSize().getHeightMils(), document.getFd());
        } catch (IOException | RuntimeException exception) {
            finish(request.id);
            if (mBridge != null) mBridge.cancel(request.id);
            request.failed();
        }
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob job) {
        mJobs.remove(job.getId());
        for (Request request : new ArrayList<>(mRequests.values())) {
            if (request.job == null || !request.job.getId().equals(job.getId())) continue;
            finish(request.id);
            if (mBridge != null) mBridge.cancel(request.id);
        }
        // Provider API has no physical printer cancel event; cancel local tracking only.
        job.cancel();
    }

    private void failJob(PrintJob job) {
        if (mJobs.remove(job.getId()) != null && !job.isCancelled() && !job.isCompleted()) {
            job.fail(getString(R.string.reaper_extension_print_failed));
        }
    }
    private void failAllJobs() {
        for (PrintJob job : new ArrayList<>(mJobs.values())) failJob(job);
    }

    @Override
    public void onPrinters(int id, String json, boolean done) {
        Request request = mRequests.get(id);
        if (request == null) return;
        try { request.printers(new JSONArray(json)); }
        catch (Exception exception) {
            finish(id);
            if (mBridge != null) mBridge.cancel(id);
            request.failed();
        }
        if (done) finish(id);
    }
    @Override
    public void onCapabilities(int id, String json) {
        Request request = finish(id);
        if (request == null) return;
        try { request.capabilities(json); }
        catch (Exception exception) { request.failed(); }
    }
    @Override
    public void onPrintResult(int id, String error) {
        Request request = finish(id);
        if (request != null) request.printed(error);
    }

    private void disconnect() {
        mConnected = false;
        mStarting = false;
        ++mStartupGeneration;
        ArrayList<Request> requests = new ArrayList<>(mRequests.values());
        mRequests.clear();
        mQueryQueue.clear();
        mQueriesInFlight = 0;
        mHandler.removeCallbacksAndMessages(null);
        ReaperExtensionPrinterBridge bridge = mBridge;
        mBridge = null;
        if (bridge != null) bridge.destroy();
        for (Request request : requests) request.failed();
        failAllJobs();
    }
    @Override
    protected void onDisconnected() { disconnect(); }
    @Override
    public void onDestroy() {
        mDestroyed = true;
        disconnect();
        super.onDestroy();
    }
}
