/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.controller;

import static org.hyperledger.besu.ethereum.eth.sync.SyncMode.isCheckpointSync;

import org.hyperledger.besu.cli.config.EthNetworkConfig;
import org.hyperledger.besu.config.GenesisConfigFile;
import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.config.PowAlgorithm;
import org.hyperledger.besu.config.QbftConfigOptions;
import org.hyperledger.besu.cryptoservices.NodeKey;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.methods.JsonRpcMethods;
import org.hyperledger.besu.ethereum.blockcreation.MiningCoordinator;
import org.hyperledger.besu.ethereum.core.MiningParameters;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.core.Synchronizer;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManager;
import org.hyperledger.besu.ethereum.eth.sync.SyncMode;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.p2p.config.SubProtocolConfiguration;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.units.bigints.UInt256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Besu controller. */
public class BesuController implements java.io.Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(BesuController.class);

  /** The constant DATABASE_PATH. */
  public static final String DATABASE_PATH = "database";
  /** The constant CACHE_PATH. */
  public static final String CACHE_PATH = "caches";

  private final ProtocolSchedule protocolSchedule;
  private final ProtocolContext protocolContext;
  private final EthProtocolManager ethProtocolManager;
  private final GenesisConfigOptions genesisConfigOptions;
  private final SubProtocolConfiguration subProtocolConfiguration;
  private final NodeKey nodeKey;
  private final Synchronizer synchronizer;
  private final JsonRpcMethods additionalJsonRpcMethodsFactory;

  private final TransactionPool transactionPool;
  private final MiningCoordinator miningCoordinator;
  private final PrivacyParameters privacyParameters;
  private final List<Closeable> closeables;
  private final MiningParameters miningParameters;
  private final PluginServiceFactory additionalPluginServices;
  private final SyncState syncState;

  /**
   * Instantiates a new Besu controller.
   *
   * @param protocolSchedule the protocol schedule
   * @param protocolContext the protocol context
   * @param ethProtocolManager the eth protocol manager
   * @param genesisConfigOptions the genesis config options
   * @param subProtocolConfiguration the sub protocol configuration
   * @param synchronizer the synchronizer
   * @param syncState the sync state
   * @param transactionPool the transaction pool
   * @param miningCoordinator the mining coordinator
   * @param privacyParameters the privacy parameters
   * @param miningParameters the mining parameters
   * @param additionalJsonRpcMethodsFactory the additional json rpc methods factory
   * @param nodeKey the node key
   * @param closeables the closeables
   * @param additionalPluginServices the additional plugin services
   */
  BesuController(
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final EthProtocolManager ethProtocolManager,
      final GenesisConfigOptions genesisConfigOptions,
      final SubProtocolConfiguration subProtocolConfiguration,
      final Synchronizer synchronizer,
      final SyncState syncState,
      final TransactionPool transactionPool,
      final MiningCoordinator miningCoordinator,
      final PrivacyParameters privacyParameters,
      final MiningParameters miningParameters,
      final JsonRpcMethods additionalJsonRpcMethodsFactory,
      final NodeKey nodeKey,
      final List<Closeable> closeables,
      final PluginServiceFactory additionalPluginServices) {
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
    this.ethProtocolManager = ethProtocolManager;
    this.genesisConfigOptions = genesisConfigOptions;
    this.subProtocolConfiguration = subProtocolConfiguration;
    this.synchronizer = synchronizer;
    this.syncState = syncState;
    this.additionalJsonRpcMethodsFactory = additionalJsonRpcMethodsFactory;
    this.nodeKey = nodeKey;
    this.transactionPool = transactionPool;
    this.miningCoordinator = miningCoordinator;
    this.privacyParameters = privacyParameters;
    this.closeables = closeables;
    this.miningParameters = miningParameters;
    this.additionalPluginServices = additionalPluginServices;
  }

  /**
   * Gets protocol context.
   *
   * @return the protocol context
   */
  public ProtocolContext getProtocolContext() {
    return protocolContext;
  }

  /**
   * Gets protocol schedule.
   *
   * @return the protocol schedule
   */
  public ProtocolSchedule getProtocolSchedule() {
    return protocolSchedule;
  }

  /**
   * Gets protocol manager.
   *
   * @return the protocol manager
   */
  public EthProtocolManager getProtocolManager() {
    return ethProtocolManager;
  }

  /**
   * Gets genesis config options.
   *
   * @return the genesis config options
   */
  public GenesisConfigOptions getGenesisConfigOptions() {
    return genesisConfigOptions;
  }

  /**
   * Gets synchronizer.
   *
   * @return the synchronizer
   */
  public Synchronizer getSynchronizer() {
    return synchronizer;
  }

  /**
   * Gets sub protocol configuration.
   *
   * @return the sub protocol configuration
   */
  public SubProtocolConfiguration getSubProtocolConfiguration() {
    return subProtocolConfiguration;
  }

  /**
   * Gets node key.
   *
   * @return the node key
   */
  public NodeKey getNodeKey() {
    return nodeKey;
  }

  /**
   * Gets transaction pool.
   *
   * @return the transaction pool
   */
  public TransactionPool getTransactionPool() {
    return transactionPool;
  }

  /**
   * Gets mining coordinator.
   *
   * @return the mining coordinator
   */
  public MiningCoordinator getMiningCoordinator() {
    return miningCoordinator;
  }

  @Override
  public void close() {
    closeables.forEach(this::tryClose);
  }

  private void tryClose(final Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException e) {
      LOG.error("Unable to close resource.", e);
    }
  }

  /**
   * Gets privacy parameters.
   *
   * @return the privacy parameters
   */
  public PrivacyParameters getPrivacyParameters() {
    return privacyParameters;
  }

  /**
   * Gets mining parameters.
   *
   * @return the mining parameters
   */
  public MiningParameters getMiningParameters() {
    return miningParameters;
  }

  /**
   * Gets additional json rpc methods.
   *
   * @param enabledRpcApis the enabled rpc apis
   * @return the additional json rpc methods
   */
  public Map<String, JsonRpcMethod> getAdditionalJsonRpcMethods(
      final Collection<String> enabledRpcApis) {
    return additionalJsonRpcMethodsFactory.create(enabledRpcApis);
  }

  /**
   * Gets sync state.
   *
   * @return the sync state
   */
  public SyncState getSyncState() {
    return syncState;
  }

  /**
   * Gets additional plugin services.
   *
   * @return the additional plugin services
   */
  public PluginServiceFactory getAdditionalPluginServices() {
    return additionalPluginServices;
  }

  /** The type Builder. */
  public static class Builder {

    /**
     * From eth network config besu controller builder.
     *
     * @param ethNetworkConfig the eth network config
     * @param genesisConfigOverrides the genesis config overrides
     * @param syncMode The sync mode
     * @return the besu controller builder
     */
    public BesuControllerBuilder fromEthNetworkConfig(
        final EthNetworkConfig ethNetworkConfig,
        final Map<String, String> genesisConfigOverrides,
        final SyncMode syncMode) {
      return fromGenesisConfig(
              GenesisConfigFile.fromConfig(ethNetworkConfig.getGenesisConfig()),
              genesisConfigOverrides,
              syncMode)
          .networkId(ethNetworkConfig.getNetworkId());
    }

    /**
     * From genesis config besu controller builder.
     *
     * @param genesisConfig the genesis config
     * @param syncMode The Sync Mode
     * @return the besu controller builder
     */
    public BesuControllerBuilder fromGenesisConfig(
        final GenesisConfigFile genesisConfig, final SyncMode syncMode) {
      return fromGenesisConfig(genesisConfig, Collections.emptyMap(), syncMode);
    }

    /**
     * From genesis config besu controller builder.
     *
     * @param genesisConfig the genesis config
     * @param genesisConfigOverrides the genesis config overrides
     * @return the besu controller builder
     */
    BesuControllerBuilder fromGenesisConfig(
        final GenesisConfigFile genesisConfig,
        final Map<String, String> genesisConfigOverrides,
        final SyncMode syncMode) {
      final GenesisConfigOptions configOptions =
          genesisConfig.getConfigOptions(genesisConfigOverrides);
      final BesuControllerBuilder builder;

      if (configOptions.isConsensusMigration()) {
        return createConsensusScheduleBesuControllerBuilder(genesisConfig, configOptions);
      }

      if (configOptions.getPowAlgorithm() != PowAlgorithm.UNSUPPORTED) {
        builder = new MainnetBesuControllerBuilder();
      } else if (configOptions.isIbft2()) {
        builder = new IbftBesuControllerBuilder();
      } else if (configOptions.isIbftLegacy()) {
        builder = new IbftLegacyBesuControllerBuilder();
      } else if (configOptions.isQbft()) {
        builder = new QbftBesuControllerBuilder();
      } else if (configOptions.isClique()) {
        builder = new CliqueBesuControllerBuilder();
      } else {
        throw new IllegalArgumentException("Unknown consensus mechanism defined");
      }

      // wrap with TransitionBesuControllerBuilder if we have a terminal total difficulty:
      if (configOptions.getTerminalTotalDifficulty().isPresent()) {
        // Enable start with vanilla MergeBesuControllerBuilder for PoS checkpoint block
        if (isCheckpointSync(syncMode) && isCheckpointPoSBlock(configOptions)) {
          return new MergeBesuControllerBuilder().genesisConfigFile(genesisConfig);
        } else {
          // TODO this should be changed to vanilla MergeBesuControllerBuilder and the Transition*
          // series of classes removed after we successfully transition to PoS
          // https://github.com/hyperledger/besu/issues/2897
          return new TransitionBesuControllerBuilder(builder, new MergeBesuControllerBuilder())
              .genesisConfigFile(genesisConfig);
        }

      } else return builder.genesisConfigFile(genesisConfig);
    }

    private BesuControllerBuilder createConsensusScheduleBesuControllerBuilder(
        final GenesisConfigFile genesisConfig, final GenesisConfigOptions configOptions) {
      final Map<Long, BesuControllerBuilder> besuControllerBuilderSchedule = new HashMap<>();

      final BesuControllerBuilder originalControllerBuilder;
      if (configOptions.isIbft2()) {
        originalControllerBuilder = new IbftBesuControllerBuilder();
      } else if (configOptions.isIbftLegacy()) {
        originalControllerBuilder = new IbftLegacyBesuControllerBuilder();
      } else {
        throw new IllegalStateException(
            "Invalid genesis migration config. Migration is supported from IBFT (legacy) or IBFT2 to QBFT)");
      }
      besuControllerBuilderSchedule.put(0L, originalControllerBuilder);

      final QbftConfigOptions qbftConfigOptions = configOptions.getQbftConfigOptions();
      final Long qbftBlock = readQbftStartBlockConfig(qbftConfigOptions);
      besuControllerBuilderSchedule.put(qbftBlock, new QbftBesuControllerBuilder());

      return new ConsensusScheduleBesuControllerBuilder(besuControllerBuilderSchedule)
          .genesisConfigFile(genesisConfig);
    }

    private Long readQbftStartBlockConfig(final QbftConfigOptions qbftConfigOptions) {
      final long startBlock =
          qbftConfigOptions
              .getStartBlock()
              .orElseThrow(
                  () ->
                      new IllegalStateException("Missing QBFT startBlock config in genesis file"));

      if (startBlock <= 0) {
        throw new IllegalStateException("Invalid QBFT startBlock config in genesis file");
      }

      return startBlock;
    }

    private boolean isCheckpointPoSBlock(final GenesisConfigOptions configOptions) {
      final UInt256 terminalTotalDifficulty = configOptions.getTerminalTotalDifficulty().get();

      return configOptions.getCheckpointOptions().isValid()
          && (UInt256.fromHexString(configOptions.getCheckpointOptions().getTotalDifficulty().get())
              .greaterThan(terminalTotalDifficulty));
    }
  }
}
