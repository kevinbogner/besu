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
package org.hyperledger.besu.ethereum.mainnet;

import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.config.PowAlgorithm;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.GasLimitCalculator;
import org.hyperledger.besu.ethereum.MainnetBlockValidator;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Deposit;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.Withdrawal;
import org.hyperledger.besu.ethereum.core.feemarket.CoinbaseFeePriceCalculator;
import org.hyperledger.besu.ethereum.goquorum.GoQuorumBlockProcessor;
import org.hyperledger.besu.ethereum.goquorum.GoQuorumBlockValidator;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecBuilder.BlockProcessorBuilder;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecBuilder.BlockValidatorBuilder;
import org.hyperledger.besu.ethereum.mainnet.feemarket.BaseFeeMarket;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.ethereum.privacy.PrivateTransactionProcessor;
import org.hyperledger.besu.ethereum.privacy.PrivateTransactionValidator;
import org.hyperledger.besu.ethereum.privacy.storage.PrivateMetadataUpdater;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.contractvalidation.EOFValidationCodeRule;
import org.hyperledger.besu.evm.contractvalidation.MaxCodeSizeRule;
import org.hyperledger.besu.evm.contractvalidation.PrefixCodeRule;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.BerlinGasCalculator;
import org.hyperledger.besu.evm.gascalculator.ByzantiumGasCalculator;
import org.hyperledger.besu.evm.gascalculator.CancunGasCalculator;
import org.hyperledger.besu.evm.gascalculator.ConstantinopleGasCalculator;
import org.hyperledger.besu.evm.gascalculator.FrontierGasCalculator;
import org.hyperledger.besu.evm.gascalculator.HomesteadGasCalculator;
import org.hyperledger.besu.evm.gascalculator.IstanbulGasCalculator;
import org.hyperledger.besu.evm.gascalculator.LondonGasCalculator;
import org.hyperledger.besu.evm.gascalculator.PetersburgGasCalculator;
import org.hyperledger.besu.evm.gascalculator.ShanghaiGasCalculator;
import org.hyperledger.besu.evm.gascalculator.SpuriousDragonGasCalculator;
import org.hyperledger.besu.evm.gascalculator.TangerineWhistleGasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.worldstate.WorldState;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.data.TransactionType;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.io.Resources;
import io.vertx.core.json.JsonArray;

/** Provides the various {@link ProtocolSpec}s on mainnet hard forks. */
public abstract class MainnetProtocolSpecs {

  public static final int FRONTIER_CONTRACT_SIZE_LIMIT = Integer.MAX_VALUE;

  public static final int SPURIOUS_DRAGON_CONTRACT_SIZE_LIMIT = 24576;
  public static final int SHANGHAI_INIT_CODE_SIZE_LIMIT = 2 * SPURIOUS_DRAGON_CONTRACT_SIZE_LIMIT;

  private static final Address RIPEMD160_PRECOMPILE =
      Address.fromHexString("0x0000000000000000000000000000000000000003");

  // A consensus bug at Ethereum mainnet transaction 0xcf416c53
  // deleted an empty account even when the message execution scope
  // failed, but the transaction itself succeeded.
  private static final Set<Address> SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES =
      Set.of(RIPEMD160_PRECOMPILE);

  private static final Wei FRONTIER_BLOCK_REWARD = Wei.fromEth(5);

  private static final Wei BYZANTIUM_BLOCK_REWARD = Wei.fromEth(3);

  private static final Wei CONSTANTINOPLE_BLOCK_REWARD = Wei.fromEth(2);

  private MainnetProtocolSpecs() {}

  public static ProtocolSpecBuilder frontierDefinition(
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean goQuorumMode,
      final EvmConfiguration evmConfiguration) {
    final int contractSizeLimit = configContractSizeLimit.orElse(FRONTIER_CONTRACT_SIZE_LIMIT);
    final int stackSizeLimit = configStackSizeLimit.orElse(MessageFrame.DEFAULT_MAX_STACK_SIZE);
    return new ProtocolSpecBuilder()
        .gasCalculator(FrontierGasCalculator::new)
        .gasLimitCalculator(new FrontierTargetingGasLimitCalculator())
        .evmBuilder(MainnetEVMs::frontier)
        .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::frontier)
        .messageCallProcessorBuilder(MessageCallProcessor::new)
        .contractCreationProcessorBuilder(
            (gasCalculator, evm) ->
                new ContractCreationProcessor(
                    gasCalculator,
                    evm,
                    false,
                    Collections.singletonList(MaxCodeSizeRule.of(contractSizeLimit)),
                    0))
        .transactionValidatorBuilder(
            (gasCalculator, gasLimitCalculator) ->
                new MainnetTransactionValidator(
                    gasCalculator, gasLimitCalculator, false, Optional.empty(), goQuorumMode))
        .transactionProcessorBuilder(
            (gasCalculator,
                transactionValidator,
                contractCreationProcessor,
                messageCallProcessor) ->
                new MainnetTransactionProcessor(
                    gasCalculator,
                    transactionValidator,
                    contractCreationProcessor,
                    messageCallProcessor,
                    false,
                    false,
                    stackSizeLimit,
                    FeeMarket.legacy(),
                    CoinbaseFeePriceCalculator.frontier()))
        .privateTransactionProcessorBuilder(
            (transactionValidator,
                contractCreationProcessor,
                messageCallProcessor,
                privateTransactionValidator) ->
                new PrivateTransactionProcessor(
                    transactionValidator,
                    contractCreationProcessor,
                    messageCallProcessor,
                    false,
                    stackSizeLimit,
                    new PrivateTransactionValidator(Optional.empty())))
        .difficultyCalculator(MainnetDifficultyCalculators.FRONTIER)
        .blockHeaderValidatorBuilder(feeMarket -> MainnetBlockHeaderValidator.create())
        .ommerHeaderValidatorBuilder(
            feeMarket -> MainnetBlockHeaderValidator.createLegacyFeeMarketOmmerValidator())
        .blockBodyValidatorBuilder(MainnetBlockBodyValidator::new)
        .transactionReceiptFactory(MainnetProtocolSpecs::frontierTransactionReceiptFactory)
        .blockReward(FRONTIER_BLOCK_REWARD)
        .skipZeroBlockRewards(false)
        .blockProcessorBuilder(MainnetProtocolSpecs.blockProcessorBuilder(goQuorumMode))
        .blockValidatorBuilder(MainnetProtocolSpecs.blockValidatorBuilder(goQuorumMode))
        .blockImporterBuilder(MainnetBlockImporter::new)
        .blockHeaderFunctions(new MainnetBlockHeaderFunctions())
        .miningBeneficiaryCalculator(BlockHeader::getCoinbase)
        .evmConfiguration(evmConfiguration)
        .name("Frontier");
  }

  public static PoWHasher powHasher(final PowAlgorithm powAlgorithm) {
    if (powAlgorithm == null) {
      return PoWHasher.UNSUPPORTED;
    }
    switch (powAlgorithm) {
      case ETHASH:
        return PoWHasher.ETHASH_LIGHT;
      case KECCAK256:
        return KeccakHasher.KECCAK256;
      case UNSUPPORTED:
      default:
        return PoWHasher.UNSUPPORTED;
    }
  }

  public static BlockValidatorBuilder blockValidatorBuilder(final boolean goQuorumMode) {
    if (goQuorumMode) {
      return GoQuorumBlockValidator::new;
    } else {
      return (blockHeaderValidator,
          blockBodyValidator,
          blockProcessor,
          badBlockManager,
          goQuorumPrivacyParameters) ->
          new MainnetBlockValidator(
              blockHeaderValidator, blockBodyValidator, blockProcessor, badBlockManager);
    }
  }

  public static BlockProcessorBuilder blockProcessorBuilder(final boolean goQuorumMode) {
    if (goQuorumMode) {
      return GoQuorumBlockProcessor::new;
    } else {
      return MainnetBlockProcessor::new;
    }
  }

  public static ProtocolSpecBuilder homesteadDefinition(
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    final int contractSizeLimit = configContractSizeLimit.orElse(FRONTIER_CONTRACT_SIZE_LIMIT);
    return frontierDefinition(
            configContractSizeLimit,
            configStackSizeLimit,
            quorumCompatibilityMode,
            evmConfiguration)
        .gasCalculator(HomesteadGasCalculator::new)
        .evmBuilder(MainnetEVMs::homestead)
        .contractCreationProcessorBuilder(
            (gasCalculator, evm) ->
                new ContractCreationProcessor(
                    gasCalculator,
                    evm,
                    true,
                    Collections.singletonList(MaxCodeSizeRule.of(contractSizeLimit)),
                    0))
        .transactionValidatorBuilder(
            (gasCalculator, gasLimitCalculator) ->
                new MainnetTransactionValidator(
                    gasCalculator,
                    gasLimitCalculator,
                    true,
                    Optional.empty(),
                    quorumCompatibilityMode))
        .difficultyCalculator(MainnetDifficultyCalculators.HOMESTEAD)
        .name("Homestead");
  }

  public static ProtocolSpecBuilder daoRecoveryInitDefinition(
      final OptionalInt contractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    return homesteadDefinition(
            contractSizeLimit, configStackSizeLimit, quorumCompatibilityMode, evmConfiguration)
        .blockHeaderValidatorBuilder(feeMarket -> MainnetBlockHeaderValidator.createDaoValidator())
        .blockProcessorBuilder(
            (transactionProcessor,
                transactionReceiptFactory,
                blockReward,
                miningBeneficiaryCalculator,
                skipZeroBlockRewards,
                goQuorumPrivacyParameters,
                protocolSchedule) ->
                new DaoBlockProcessor(
                    new MainnetBlockProcessor(
                        transactionProcessor,
                        transactionReceiptFactory,
                        blockReward,
                        miningBeneficiaryCalculator,
                        skipZeroBlockRewards,
                        Optional.empty(),
                        protocolSchedule)))
        .name("DaoRecoveryInit");
  }

  public static ProtocolSpecBuilder daoRecoveryTransitionDefinition(
      final OptionalInt contractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    return daoRecoveryInitDefinition(
            contractSizeLimit, configStackSizeLimit, quorumCompatibilityMode, evmConfiguration)
        .blockProcessorBuilder(MainnetBlockProcessor::new)
        .name("DaoRecoveryTransition");
  }

  public static ProtocolSpecBuilder tangerineWhistleDefinition(
      final OptionalInt contractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    return homesteadDefinition(
            contractSizeLimit, configStackSizeLimit, quorumCompatibilityMode, evmConfiguration)
        .gasCalculator(TangerineWhistleGasCalculator::new)
        .name("TangerineWhistle");
  }

  public static ProtocolSpecBuilder spuriousDragonDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    final int contractSizeLimit =
        configContractSizeLimit.orElse(SPURIOUS_DRAGON_CONTRACT_SIZE_LIMIT);
    final int stackSizeLimit = configStackSizeLimit.orElse(MessageFrame.DEFAULT_MAX_STACK_SIZE);

    return tangerineWhistleDefinition(
            OptionalInt.empty(), configStackSizeLimit, quorumCompatibilityMode, evmConfiguration)
        .gasCalculator(SpuriousDragonGasCalculator::new)
        .skipZeroBlockRewards(true)
        .messageCallProcessorBuilder(
            (evm, precompileContractRegistry) ->
                new MessageCallProcessor(
                    evm,
                    precompileContractRegistry,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES))
        .contractCreationProcessorBuilder(
            (gasCalculator, evm) ->
                new ContractCreationProcessor(
                    gasCalculator,
                    evm,
                    true,
                    Collections.singletonList(MaxCodeSizeRule.of(contractSizeLimit)),
                    1,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES))
        .transactionValidatorBuilder(
            (gasCalculator, gasLimitCalculator) ->
                new MainnetTransactionValidator(
                    gasCalculator, gasLimitCalculator, true, chainId, quorumCompatibilityMode))
        .transactionProcessorBuilder(
            (gasCalculator,
                transactionValidator,
                contractCreationProcessor,
                messageCallProcessor) ->
                new MainnetTransactionProcessor(
                    gasCalculator,
                    transactionValidator,
                    contractCreationProcessor,
                    messageCallProcessor,
                    true,
                    false,
                    stackSizeLimit,
                    FeeMarket.legacy(),
                    CoinbaseFeePriceCalculator.frontier()))
        .name("SpuriousDragon");
  }

  public static ProtocolSpecBuilder byzantiumDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt contractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    final int stackSizeLimit = configStackSizeLimit.orElse(MessageFrame.DEFAULT_MAX_STACK_SIZE);
    return spuriousDragonDefinition(
            chainId,
            contractSizeLimit,
            configStackSizeLimit,
            quorumCompatibilityMode,
            evmConfiguration)
        .gasCalculator(ByzantiumGasCalculator::new)
        .evmBuilder(MainnetEVMs::byzantium)
        .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::byzantium)
        .difficultyCalculator(MainnetDifficultyCalculators.BYZANTIUM)
        .transactionReceiptFactory(
            enableRevertReason
                ? MainnetProtocolSpecs::byzantiumTransactionReceiptFactoryWithReasonEnabled
                : MainnetProtocolSpecs::byzantiumTransactionReceiptFactory)
        .blockReward(BYZANTIUM_BLOCK_REWARD)
        .privateTransactionValidatorBuilder(() -> new PrivateTransactionValidator(chainId))
        .privateTransactionProcessorBuilder(
            (transactionValidator,
                contractCreationProcessor,
                messageCallProcessor,
                privateTransactionValidator) ->
                new PrivateTransactionProcessor(
                    transactionValidator,
                    contractCreationProcessor,
                    messageCallProcessor,
                    false,
                    stackSizeLimit,
                    privateTransactionValidator))
        .name("Byzantium");
  }

  public static ProtocolSpecBuilder constantinopleDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt contractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    return byzantiumDefinition(
            chainId,
            contractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            quorumCompatibilityMode,
            evmConfiguration)
        .difficultyCalculator(MainnetDifficultyCalculators.CONSTANTINOPLE)
        .gasCalculator(ConstantinopleGasCalculator::new)
        .evmBuilder(MainnetEVMs::constantinople)
        .blockReward(CONSTANTINOPLE_BLOCK_REWARD)
        .name("Constantinople");
  }

  public static ProtocolSpecBuilder petersburgDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt contractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    return constantinopleDefinition(
            chainId,
            contractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            quorumCompatibilityMode,
            evmConfiguration)
        .gasCalculator(PetersburgGasCalculator::new)
        .name("Petersburg");
  }

  public static ProtocolSpecBuilder istanbulDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    final int contractSizeLimit =
        configContractSizeLimit.orElse(SPURIOUS_DRAGON_CONTRACT_SIZE_LIMIT);
    return petersburgDefinition(
            chainId,
            configContractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            quorumCompatibilityMode,
            evmConfiguration)
        .gasCalculator(IstanbulGasCalculator::new)
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.istanbul(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::istanbul)
        .contractCreationProcessorBuilder(
            (gasCalculator, evm) ->
                new ContractCreationProcessor(
                    gasCalculator,
                    evm,
                    true,
                    Collections.singletonList(MaxCodeSizeRule.of(contractSizeLimit)),
                    1,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES))
        .name("Istanbul");
  }

  static ProtocolSpecBuilder muirGlacierDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt contractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    return istanbulDefinition(
            chainId,
            contractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            quorumCompatibilityMode,
            evmConfiguration)
        .difficultyCalculator(MainnetDifficultyCalculators.MUIR_GLACIER)
        .name("MuirGlacier");
  }

  static ProtocolSpecBuilder berlinDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt contractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    return muirGlacierDefinition(
            chainId,
            contractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            quorumCompatibilityMode,
            evmConfiguration)
        .gasCalculator(BerlinGasCalculator::new)
        .transactionValidatorBuilder(
            (gasCalculator, gasLimitCalculator) ->
                new MainnetTransactionValidator(
                    gasCalculator,
                    gasLimitCalculator,
                    true,
                    chainId,
                    Set.of(TransactionType.FRONTIER, TransactionType.ACCESS_LIST),
                    quorumCompatibilityMode))
        .transactionReceiptFactory(
            enableRevertReason
                ? MainnetProtocolSpecs::berlinTransactionReceiptFactoryWithReasonEnabled
                : MainnetProtocolSpecs::berlinTransactionReceiptFactory)
        .name("Berlin");
  }

  static ProtocolSpecBuilder londonDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    final int contractSizeLimit =
        configContractSizeLimit.orElse(SPURIOUS_DRAGON_CONTRACT_SIZE_LIMIT);
    final int stackSizeLimit = configStackSizeLimit.orElse(MessageFrame.DEFAULT_MAX_STACK_SIZE);
    final long londonForkBlockNumber =
        genesisConfigOptions.getLondonBlockNumber().orElse(Long.MAX_VALUE);
    final BaseFeeMarket londonFeeMarket =
        genesisConfigOptions.isZeroBaseFee()
            ? FeeMarket.zeroBaseFee(londonForkBlockNumber)
            : FeeMarket.london(londonForkBlockNumber, genesisConfigOptions.getBaseFeePerGas());
    return berlinDefinition(
            chainId,
            configContractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            quorumCompatibilityMode,
            evmConfiguration)
        .gasCalculator(LondonGasCalculator::new)
        .gasLimitCalculator(
            new LondonTargetingGasLimitCalculator(londonForkBlockNumber, londonFeeMarket))
        .transactionValidatorBuilder(
            (gasCalculator, gasLimitCalculator) ->
                new MainnetTransactionValidator(
                    gasCalculator,
                    gasLimitCalculator,
                    londonFeeMarket,
                    true,
                    chainId,
                    Set.of(
                        TransactionType.FRONTIER,
                        TransactionType.ACCESS_LIST,
                        TransactionType.EIP1559),
                    quorumCompatibilityMode,
                    Integer.MAX_VALUE))
        .transactionProcessorBuilder(
            (gasCalculator,
                transactionValidator,
                contractCreationProcessor,
                messageCallProcessor) ->
                new MainnetTransactionProcessor(
                    gasCalculator,
                    transactionValidator,
                    contractCreationProcessor,
                    messageCallProcessor,
                    true,
                    false,
                    stackSizeLimit,
                    londonFeeMarket,
                    CoinbaseFeePriceCalculator.eip1559()))
        .contractCreationProcessorBuilder(
            (gasCalculator, evm) ->
                new ContractCreationProcessor(
                    gasCalculator,
                    evm,
                    true,
                    List.of(MaxCodeSizeRule.of(contractSizeLimit), PrefixCodeRule.of()),
                    1,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES))
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.london(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .feeMarket(londonFeeMarket)
        .difficultyCalculator(MainnetDifficultyCalculators.LONDON)
        .blockHeaderValidatorBuilder(
            feeMarket -> MainnetBlockHeaderValidator.createBaseFeeMarketValidator(londonFeeMarket))
        .ommerHeaderValidatorBuilder(
            feeMarket ->
                MainnetBlockHeaderValidator.createBaseFeeMarketOmmerValidator(londonFeeMarket))
        .blockBodyValidatorBuilder(BaseFeeBlockBodyValidator::new)
        .name("London");
  }

  static ProtocolSpecBuilder arrowGlacierDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    return londonDefinition(
            chainId,
            configContractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            genesisConfigOptions,
            quorumCompatibilityMode,
            evmConfiguration)
        .difficultyCalculator(MainnetDifficultyCalculators.ARROW_GLACIER)
        .name("ArrowGlacier");
  }

  static ProtocolSpecBuilder grayGlacierDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {
    return arrowGlacierDefinition(
            chainId,
            configContractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            genesisConfigOptions,
            quorumCompatibilityMode,
            evmConfiguration)
        .difficultyCalculator(MainnetDifficultyCalculators.GRAY_GLACIER)
        .name("GrayGlacier");
  }

  static ProtocolSpecBuilder parisDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {

    return grayGlacierDefinition(
            chainId,
            configContractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            genesisConfigOptions,
            quorumCompatibilityMode,
            evmConfiguration)
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.paris(gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .difficultyCalculator(MainnetDifficultyCalculators.PROOF_OF_STAKE_DIFFICULTY)
        .blockHeaderValidatorBuilder(MainnetBlockHeaderValidator::mergeBlockHeaderValidator)
        .blockReward(Wei.ZERO)
        .name("ParisFork")
        .isPoS(true);
  }

  static ProtocolSpecBuilder shanghaiDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {

    // extra variables need to support flipping the warm coinbase flag.
    final int stackSizeLimit = configStackSizeLimit.orElse(MessageFrame.DEFAULT_MAX_STACK_SIZE);
    final long londonForkBlockNumber = genesisConfigOptions.getLondonBlockNumber().orElse(0L);
    final BaseFeeMarket londonFeeMarket =
        genesisConfigOptions.isZeroBaseFee()
            ? FeeMarket.zeroBaseFee(londonForkBlockNumber)
            : FeeMarket.london(londonForkBlockNumber, genesisConfigOptions.getBaseFeePerGas());

    return parisDefinition(
            chainId,
            configContractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            genesisConfigOptions,
            quorumCompatibilityMode,
            evmConfiguration)
        // gas calculator has new code to support EIP-3860 limit and meter initcode
        .gasCalculator(ShanghaiGasCalculator::new)
        // EVM has a new operation for EIP-3855 PUSH0 instruction
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.shanghai(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        // we need to flip the Warm Coinbase flag for EIP-3651 warm coinbase
        .transactionProcessorBuilder(
            (gasCalculator,
                transactionValidator,
                contractCreationProcessor,
                messageCallProcessor) ->
                new MainnetTransactionProcessor(
                    gasCalculator,
                    transactionValidator,
                    contractCreationProcessor,
                    messageCallProcessor,
                    true,
                    true,
                    stackSizeLimit,
                    londonFeeMarket,
                    CoinbaseFeePriceCalculator.eip1559()))
        // Contract creation rules for EIP-3860 Limit and meter intitcode
        .transactionValidatorBuilder(
            (gasCalculator, gasLimitCalculator) ->
                new MainnetTransactionValidator(
                    gasCalculator,
                    gasLimitCalculator,
                    londonFeeMarket,
                    true,
                    chainId,
                    Set.of(
                        TransactionType.FRONTIER,
                        TransactionType.ACCESS_LIST,
                        TransactionType.EIP1559),
                    quorumCompatibilityMode,
                    SHANGHAI_INIT_CODE_SIZE_LIMIT))
        .withdrawalsProcessor(new WithdrawalsProcessor())
        .withdrawalsValidator(new WithdrawalsValidator.AllowedWithdrawals())
        .name("Shanghai");
  }

  static ProtocolSpecBuilder cancunDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {

    final int stackSizeLimit = configStackSizeLimit.orElse(MessageFrame.DEFAULT_MAX_STACK_SIZE);
    final int contractSizeLimit =
        configContractSizeLimit.orElse(SPURIOUS_DRAGON_CONTRACT_SIZE_LIMIT);
    final long londonForkBlockNumber = genesisConfigOptions.getLondonBlockNumber().orElse(0L);
    final BaseFeeMarket cancunFeeMarket =
        genesisConfigOptions.isZeroBaseFee()
            ? FeeMarket.zeroBaseFee(londonForkBlockNumber)
            : FeeMarket.cancun(londonForkBlockNumber, genesisConfigOptions.getBaseFeePerGas());

    final GasLimitCalculator cancunGasLimitCalculator =
        new CancunTargetingGasLimitCalculator(londonForkBlockNumber, cancunFeeMarket);

    return shanghaiDefinition(
            chainId,
            configContractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            genesisConfigOptions,
            quorumCompatibilityMode,
            evmConfiguration)
        .feeMarket(cancunFeeMarket)
        // gas calculator for EIP-4844 data gas
        .gasCalculator(CancunGasCalculator::new)
        // gas limit with EIP-4844 max data gas per block
        .gasLimitCalculator(cancunGasLimitCalculator)
        // EVM changes to support EOF EIPs (3670, 4200, 4750, 5450)
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.cancun(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        // change contract call creator to accept EOF code
        .contractCreationProcessorBuilder(
            (gasCalculator, evm) ->
                new ContractCreationProcessor(
                    gasCalculator,
                    evm,
                    true,
                    List.of(
                        MaxCodeSizeRule.of(contractSizeLimit), EOFValidationCodeRule.of(1, false)),
                    1,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES))
        // use Cancun fee market
        .transactionProcessorBuilder(
            (gasCalculator,
                transactionValidator,
                contractCreationProcessor,
                messageCallProcessor) ->
                new MainnetTransactionProcessor(
                    gasCalculator,
                    transactionValidator,
                    contractCreationProcessor,
                    messageCallProcessor,
                    true,
                    true,
                    stackSizeLimit,
                    cancunFeeMarket,
                    CoinbaseFeePriceCalculator.eip1559()))
        // change to check for max data gas per block for EIP-4844
        .transactionValidatorBuilder(
            (gasCalculator, gasLimitCalculator) ->
                new MainnetTransactionValidator(
                    gasCalculator,
                    gasLimitCalculator,
                    cancunFeeMarket,
                    true,
                    chainId,
                    Set.of(
                        TransactionType.FRONTIER,
                        TransactionType.ACCESS_LIST,
                        TransactionType.EIP1559,
                        TransactionType.BLOB),
                    quorumCompatibilityMode,
                    SHANGHAI_INIT_CODE_SIZE_LIMIT))
        .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::cancun)
        .name("Cancun");
  }

  static ProtocolSpecBuilder futureEipsDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {

    return cancunDefinition(
            chainId,
            configContractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            genesisConfigOptions,
            quorumCompatibilityMode,
            evmConfiguration)
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.futureEips(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .name("FutureEips");
  }

  static ProtocolSpecBuilder experimentalEipsDefinition(
      final Optional<BigInteger> chainId,
      final OptionalInt configContractSizeLimit,
      final OptionalInt configStackSizeLimit,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final boolean quorumCompatibilityMode,
      final EvmConfiguration evmConfiguration) {

    return futureEipsDefinition(
            chainId,
            configContractSizeLimit,
            configStackSizeLimit,
            enableRevertReason,
            genesisConfigOptions,
            quorumCompatibilityMode,
            evmConfiguration)
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.experimentalEips(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .depositsValidator(new DepositsValidator.AllowedDeposits())
        .name("ExperimentalEips");
  }

  private static TransactionReceipt frontierTransactionReceiptFactory(
      // ignored because it's always FRONTIER
      final TransactionType __,
      final TransactionProcessingResult result,
      final WorldState worldState,
      final long gasUsed) {
    return new TransactionReceipt(
        worldState.frontierRootHash(),
        gasUsed,
        result.getLogs(),
        Optional.empty()); // No revert reason in frontier
  }

  private static TransactionReceipt byzantiumTransactionReceiptFactory(
      // ignored because it's always FRONTIER
      final TransactionType __,
      final TransactionProcessingResult result,
      final WorldState worldState,
      final long gasUsed) {
    return new TransactionReceipt(
        result.isSuccessful() ? 1 : 0, gasUsed, result.getLogs(), Optional.empty());
  }

  private static TransactionReceipt byzantiumTransactionReceiptFactoryWithReasonEnabled(
      // ignored because it's always FRONTIER
      final TransactionType __,
      final TransactionProcessingResult result,
      final WorldState worldState,
      final long gasUsed) {
    return new TransactionReceipt(
        result.isSuccessful() ? 1 : 0, gasUsed, result.getLogs(), result.getRevertReason());
  }

  static TransactionReceipt berlinTransactionReceiptFactory(
      final TransactionType transactionType,
      final TransactionProcessingResult transactionProcessingResult,
      final WorldState worldState,
      final long gasUsed) {
    return new TransactionReceipt(
        transactionType,
        transactionProcessingResult.isSuccessful() ? 1 : 0,
        gasUsed,
        transactionProcessingResult.getLogs(),
        Optional.empty());
  }

  static TransactionReceipt berlinTransactionReceiptFactoryWithReasonEnabled(
      final TransactionType transactionType,
      final TransactionProcessingResult transactionProcessingResult,
      final WorldState worldState,
      final long gasUsed) {
    return new TransactionReceipt(
        transactionType,
        transactionProcessingResult.isSuccessful() ? 1 : 0,
        gasUsed,
        transactionProcessingResult.getLogs(),
        transactionProcessingResult.getRevertReason());
  }

  private static class DaoBlockProcessor implements BlockProcessor {

    private final BlockProcessor wrapped;

    public DaoBlockProcessor(final BlockProcessor wrapped) {
      this.wrapped = wrapped;
    }

    @Override
    public BlockProcessingResult processBlock(
        final Blockchain blockchain,
        final MutableWorldState worldState,
        final BlockHeader blockHeader,
        final List<Transaction> transactions,
        final List<BlockHeader> ommers,
        final Optional<List<Withdrawal>> withdrawals,
        final Optional<List<Deposit>> deposits,
        final PrivateMetadataUpdater privateMetadataUpdater) {
      updateWorldStateForDao(worldState);
      return wrapped.processBlock(
          blockchain,
          worldState,
          blockHeader,
          transactions,
          ommers,
          withdrawals,
          deposits,
          privateMetadataUpdater);
    }

    private static final Address DAO_REFUND_CONTRACT_ADDRESS =
        Address.fromHexString("0xbf4ed7b27f1d666546e30d74d50d173d20bca754");

    private void updateWorldStateForDao(final MutableWorldState worldState) {
      try {
        final JsonArray json =
            new JsonArray(
                Resources.toString(
                    this.getClass().getResource("/daoAddresses.json"), StandardCharsets.UTF_8));
        final List<Address> addresses =
            IntStream.range(0, json.size())
                .mapToObj(json::getString)
                .map(Address::fromHexString)
                .collect(Collectors.toList());
        final WorldUpdater worldUpdater = worldState.updater();
        final MutableAccount daoRefundContract =
            worldUpdater.getOrCreate(DAO_REFUND_CONTRACT_ADDRESS).getMutable();
        for (final Address address : addresses) {
          final MutableAccount account = worldUpdater.getOrCreate(address).getMutable();
          final Wei balance = account.getBalance();
          account.decrementBalance(balance);
          daoRefundContract.incrementBalance(balance);
        }
        worldUpdater.commit();
      } catch (final IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
