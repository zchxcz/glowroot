/**
 * Copyright 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.testkit;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.MainEntryPoint;
import io.informant.core.util.DaemonExecutors;
import io.informant.core.util.Threads;
import io.informant.core.util.Threads.RogueThreadsException;
import io.informant.testkit.SocketCommander.CommandWrapper;
import io.informant.testkit.SocketCommander.ResponseWrapper;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class SocketCommandProcessor implements Runnable {

    public static final String EXECUTE_APP_COMMAND = "EXECUTE_APP";
    public static final String GET_PORT_COMMAND = "GET_PORT";
    public static final String EXCEPTION_RESPONSE = "EXCEPTION";
    public static final String SHUTDOWN_COMMAND = "SHUTDOWN";
    public static final String SHUTDOWN_RESPONSE = "SHUTDOWN";
    public static final String KILL_COMMAND = "KILL";
    public static final String INTERRUPT = "INTERRUPT";

    private static final Logger logger = LoggerFactory.getLogger(SocketCommandProcessor.class);

    private final ObjectInputStream objectIn;
    private final ObjectOutputStream objectOut;
    private final ExecutorService executorService =
            DaemonExecutors.newCachedThreadPool("Informant-SocketCommandProcessor");
    private final List<Thread> executingAppThreads = Lists.newCopyOnWriteArrayList();
    private ImmutableList<Thread> preExistingThreads;

    SocketCommandProcessor(ObjectInputStream objectIn, ObjectOutputStream objectOut) {
        this.objectIn = objectIn;
        this.objectOut = objectOut;
    }

    public void run() {
        try {
            while (true) {
                readCommandAndSpawnHandlerThread();
            }
        } catch (EOFException e) {
            // socket was closed, terminate gracefully
            System.exit(0);
        } catch (Throwable e) {
            // this may not get logged if test jvm has been terminated already
            logger.error(e.getMessage(), e);
            System.exit(1);
        }
    }

    private void readCommandAndSpawnHandlerThread() throws IOException, ClassNotFoundException {
        final CommandWrapper commandWrapper = (CommandWrapper) objectIn.readObject();
        logger.debug("command received by external jvm: {}", commandWrapper);
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    runCommandAndRespond(commandWrapper);
                } catch (EOFException e) {
                    // socket was closed, terminate gracefully
                    System.exit(0);
                } catch (Throwable e) {
                    // this may not get logged if test jvm has been terminated already
                    logger.error(e.getMessage(), e);
                    System.exit(1);
                }
            }
        });
    }

    private void runCommandAndRespond(CommandWrapper commandWrapper) throws Exception {
        Object command = commandWrapper.getCommand();
        int commandNum = commandWrapper.getCommandNum();
        if (command instanceof String) {
            if (command.equals(GET_PORT_COMMAND)) {
                respond(MainEntryPoint.getPort(), commandNum);
            } else if (command.equals(KILL_COMMAND)) {
                System.exit(0);
            } else if (command.equals(SHUTDOWN_COMMAND)) {
                shutdown(commandNum);
            } else if (command.equals(INTERRUPT)) {
                interruptAppAndRespond(commandNum);
            } else {
                logger.error("unexpected command '" + command + "'");
                respond(EXCEPTION_RESPONSE, commandNum);
            }
        } else if (command instanceof List) {
            List<?> argList = (List<?>) command;
            if (argList.isEmpty()) {
                logger.error("unexpected empty command");
                respond(EXCEPTION_RESPONSE, commandNum);
            } else {
                Object commandName = argList.get(0);
                argList = ImmutableList.copyOf(argList).subList(1, argList.size());
                if (commandName.equals(EXECUTE_APP_COMMAND)) {
                    executeAppAndRespond(commandNum, argList);
                } else {
                    logger.error("unexpected command '" + commandName + "'");
                    respond(EXCEPTION_RESPONSE, commandNum);
                }
            }
        } else {
            logger.error("unexpected command type '" + command.getClass().getName() + "'");
            respond(EXCEPTION_RESPONSE, commandNum);
        }
    }

    private void shutdown(int commandNum) throws IOException, InterruptedException {
        executorService.shutdown();
        if (preExistingThreads == null) {
            // EXECUTE_APP was never run
            respond(SHUTDOWN_RESPONSE, commandNum);
        } else {
            try {
                Threads.preShutdownCheck(preExistingThreads);
                MainEntryPoint.shutdown();
                Threads.postShutdownCheck(preExistingThreads);
                respond(SHUTDOWN_RESPONSE, commandNum);
            } catch (RogueThreadsException e) {
                logger.error(e.getMessage(), e);
                respond(EXCEPTION_RESPONSE, commandNum);
            }
        }
    }

    private void executeAppAndRespond(int commandNum, List<?> argList) throws Exception {
        if (preExistingThreads == null) {
            // wait until the first execute app command to capture pre-existing
            // threads, otherwise may pick up DestroyJavaVM thread
            preExistingThreads = ImmutableList.copyOf(Threads.currentThreads());
        }
        String appClassName = (String) argList.get(0);
        Class<?> appClass = Class.forName(appClassName);
        try {
            executingAppThreads.add(Thread.currentThread());
            AppUnderTest app = (AppUnderTest) appClass.newInstance();
            app.executeApp();
            respond("", commandNum);
        } catch (Throwable t) {
            // catch Throwable so response can (hopefully) be sent even under extreme
            // circumstances like OutOfMemoryError
            logger.error(t.getMessage(), t);
            respond(EXCEPTION_RESPONSE, commandNum);
        } finally {
            executingAppThreads.remove(Thread.currentThread());
        }
    }

    private void interruptAppAndRespond(int commandNum) throws Exception {
        try {
            for (Thread thread : executingAppThreads) {
                thread.interrupt();
            }
            respond("", commandNum);
        } catch (Throwable t) {
            // catch Throwable so response can (hopefully) be sent even under extreme
            // circumstances like OutOfMemoryError
            logger.error(t.getMessage(), t);
            respond(EXCEPTION_RESPONSE, commandNum);
        }
    }

    private void respond(Object response, int commandNum) throws IOException {
        ResponseWrapper responseWrapper = new ResponseWrapper(commandNum, response);
        logger.debug("sending response to unit test jvm: {}", responseWrapper);
        // sychronizing with SocketHeartbeat
        synchronized (objectOut) {
            objectOut.writeObject(responseWrapper);
            logger.debug("response sent");
        }
    }
}