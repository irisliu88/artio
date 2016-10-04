/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.stress;

import io.aeron.driver.MediaDriver;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.fix_gateway.engine.EngineConfiguration;
import uk.co.real_logic.fix_gateway.engine.FixEngine;
import uk.co.real_logic.fix_gateway.library.AcquiringSessionExistsHandler;
import uk.co.real_logic.fix_gateway.library.FixLibrary;
import uk.co.real_logic.fix_gateway.library.LibraryConfiguration;
import uk.co.real_logic.fix_gateway.validation.AuthenticationStrategy;
import uk.co.real_logic.fix_gateway.validation.MessageValidationStrategy;

import java.io.File;
import java.util.Arrays;

import static io.aeron.driver.ThreadingMode.SHARED;
import static java.util.Collections.singletonList;

public class Server implements Agent
{
    private MediaDriver mediaDriver;
    private FixEngine fixEngine;
    private FixLibrary fixLibrary;

    public Server()
    {
        final MessageValidationStrategy validationStrategy =
            MessageValidationStrategy.targetCompId(StressConfiguration.ACCEPTOR_ID)
            .and(MessageValidationStrategy.senderCompId(Arrays.asList(StressConfiguration.INITIATOR_ID)));

        final AuthenticationStrategy authenticationStrategy = AuthenticationStrategy.of(validationStrategy);

        // Static configuration lasts the duration of a FIX-Gateway instance
        final String aeronChannel = "aeron:udp?endpoint=localhost:10000";
        final EngineConfiguration configuration = new EngineConfiguration()
            .bindTo("localhost", StressConfiguration.PORT)
            .logFileDir("stress-server-logs")
            .libraryAeronChannel(aeronChannel);
        configuration.authenticationStrategy(authenticationStrategy);

        System.out.println("Server Logs at " + configuration.logFileDir());

        cleanupOldLogFileDir(configuration);

        final MediaDriver.Context context = new MediaDriver.Context()
            .threadingMode(SHARED)
            .dirsDeleteOnStart(true);
        mediaDriver = MediaDriver.launch(context);
        fixEngine = FixEngine.launch(configuration);

        final LibraryConfiguration libraryConfiguration = new LibraryConfiguration();
        libraryConfiguration.authenticationStrategy(authenticationStrategy);
        fixLibrary = FixLibrary.connect(libraryConfiguration
            .sessionAcquireHandler(StressSessionHandler::new)
            .sessionExistsHandler(new AcquiringSessionExistsHandler())
            .libraryAeronChannels(singletonList(aeronChannel)));
    }

    public static AgentRunner createServer(final IdleStrategy idleStrategy, ErrorHandler errorHandler)
    {
        return new AgentRunner(idleStrategy, errorHandler, null, new Server());
    }

    public int doWork() throws Exception
    {
        return fixLibrary.poll(1);
    }

    public String roleName()
    {
        return "stress server";
    }

    public void onClose()
    {
        fixLibrary.close();
        fixEngine.close();
        mediaDriver.close();
    }

    public static void cleanupOldLogFileDir(final EngineConfiguration configuration)
    {
        IoUtil.delete(new File(configuration.logFileDir()), true);
    }
}