/**
 * Copyright 2013 multibit.org
 *
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://opensource.org/licenses/mit-license.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multibit.network;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;
import org.multibit.ApplicationDataDirectoryLocator;
import org.multibit.Constants;
import org.multibit.Localiser;
import org.multibit.MultiBit;
import org.multibit.controller.MultiBitController;
import org.multibit.file.FileHandler;
import org.multibit.model.MultiBitModel;
import org.multibit.model.PerWalletModelData;
import org.multibit.model.WalletInfo;
import org.multibit.store.MultiBitWalletVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.bitcoin.core.DumpedPrivateKey;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.Wallet.BalanceType;

public class ReplayManagerTest extends TestCase {
    private static final Logger log = LoggerFactory.getLogger(ReplayManagerTest.class);

    private MultiBitController controller;
    private Localiser localiser;

    // The address for this private key is "1N4qu8a6NwBrxM5PvSoFh4qe6QSWmG6Xds".
    private static final String REPLAY1_PRIVATE_KEY = "5Jsokwg1ypfCPgJXv4vnhW11YWSp4anh9UbHoCZFZdwAnEpU69u";

    private static final String START_OF_REPLAY_PERIOD = "2012-09-03T10:00:00Z";
    private static final String END_OF_REPLAY_PERIOD = "2012-09-03T13:00:00Z";

    private static final BigInteger BALANCE_AT_START = BigInteger.ZERO;
    private static final BigInteger BALANCE_AFTER_REPLAY = BigInteger.valueOf(12000000);

    private SimpleDateFormat formatter;

    @Before
    public void setUp() throws Exception {
        controller = new MultiBitController();
        localiser = new Localiser();
        MultiBitModel model = new MultiBitModel(controller);

        controller.setLocaliser(localiser);
        controller.setModel(model);
    }

    @Test
    public void testReplayManagerSyncSingleWallet() throws Exception {
        // Get the system property runFunctionalTest to see if the functional
        // tests need running.
        String runFunctionalTests = System.getProperty(Constants.RUN_FUNCTIONAL_TESTS_PARAMETER);
        if (false && Boolean.TRUE.toString().equalsIgnoreCase(runFunctionalTests)) {
            // Date format is UTC with century, T time separator and Z for UTC timezone.
            formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH);
            formatter.setTimeZone(TimeZone.getTimeZone("UTC"));

            File multiBitDirectory = createMultiBitRuntime();

            // Set the application data directory to be the one we just created.
            ApplicationDataDirectoryLocator applicationDataDirectoryLocator = new ApplicationDataDirectoryLocator(multiBitDirectory);

            // Create the controller.
            final MultiBitController replayController = new MultiBitController(applicationDataDirectoryLocator);
            MultiBit.setController(controller);

            // Create the model - gets hooked up to controller automatically.
            @SuppressWarnings("unused")
            MultiBitModel model = new MultiBitModel(replayController);

            // Initialise replay manager
            ReplayManager replayManager = ReplayManager.INSTANCE;
            assertNotNull(replayManager);

            replayManager.initialise(replayController);

            String miningWalletPath = multiBitDirectory.getAbsolutePath() + File.separator + "mining.wallet";

            // Create a new wallet.
            Wallet replayWallet = new Wallet(NetworkParameters.prodNet());

            // Add in the mining key that has the coinbase transactions.
            DumpedPrivateKey replayDumpedPrivateKey = new DumpedPrivateKey(NetworkParameters.prodNet(), REPLAY1_PRIVATE_KEY);
            ECKey replayKey = replayDumpedPrivateKey.getKey();
            replayKey.setCreationTimeSeconds(formatter.parse(START_OF_REPLAY_PERIOD).getTime() / 1000);
            log.debug("replayPrivateKey getCreationTimeSeconds = " + replayKey.getCreationTimeSeconds());
            
            replayWallet.addKey(replayKey);
            PerWalletModelData perWalletModelData = new PerWalletModelData();
            perWalletModelData.setWalletInfo(new WalletInfo(miningWalletPath, MultiBitWalletVersion.PROTOBUF));
            perWalletModelData.setWallet(replayWallet);
            perWalletModelData.setWalletFilename(miningWalletPath);
            perWalletModelData.setWalletDescription("testReplayManagerSyncSingleWallet test");

            log.debug("Replay wallet before replay = \n" + replayWallet.toString());

            assertEquals(BALANCE_AT_START, replayWallet.getBalance());

            log.debug("Replaying blockchain");    
            // Create a ReplayTask to replay the replay wallet from the START_OF_REPLAY_PERIOD.
            List<PerWalletModelData> perWalletModelDataList = new ArrayList<PerWalletModelData>();
            perWalletModelDataList.add(perWalletModelData);
            
            ReplayTask replayTask = new ReplayTask(perWalletModelDataList, formatter.parse(START_OF_REPLAY_PERIOD), formatter.parse(END_OF_REPLAY_PERIOD), -1);
            replayManager.offerReplayTask(replayTask);

            // Check new balance on wallet - estimated balance should be at least the expected (may have later tx too)..

            log.debug("Replay wallet estimated balance is:\n" + replayWallet.getBalance(BalanceType.ESTIMATED).toString());
            log.debug("Replay wallet spendable balance is:\n" + replayWallet.getBalance().toString());
            assertTrue("Balance of replay wallet is incorrect", BALANCE_AFTER_REPLAY.compareTo(replayWallet.getBalance(BalanceType.ESTIMATED)) <= 0);
 
            // Print out replay wallet after replay.
            log.debug("Replay wallet after replay = \n" + replayWallet);
        } else {
            log.debug("Not running functional test: ReplayManagerTest#testReplayManagerSyncSingleWallet. Add '-DrunFunctionalTests=true' to run");
        }
    }

    /**
     * Create a working, portable runtime of MultiBit in a temporary directory.
     * 
     * @return the temporary directory the multibit runtime has been created in
     */
    private File createMultiBitRuntime() throws IOException {
        File multiBitDirectory = FileHandler.createTempDirectory("multibit");
        String multiBitDirectoryPath = multiBitDirectory.getAbsolutePath();

        System.out.println("Building MultiBit runtime in : " + multiBitDirectory.getAbsolutePath());

        // Create an empty multibit.properties.
        File multibitProperties = new File(multiBitDirectoryPath + File.separator + "multibit.properties");
        multibitProperties.createNewFile();
        multibitProperties.deleteOnExit();

        // Copy in the checkpoints and blockchain stored in git - this is in
        // source/main/resources/.
        File multibitBlockcheckpoints = new File(multiBitDirectoryPath + File.separator + "multibit.checkpoints");
        FileHandler.copyFile(new File("./src/main/resources/multibit.checkpoints"), multibitBlockcheckpoints);
        multibitBlockcheckpoints.deleteOnExit();
        
        File multibitBlockchain = new File(multiBitDirectoryPath + File.separator + "multibit.blockchain");
        FileHandler.copyFile(new File("./src/main/resources/multibit.blockchain"), multibitBlockchain);
        multibitBlockchain.deleteOnExit();

        return multiBitDirectory;
    }

}
