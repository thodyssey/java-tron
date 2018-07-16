package org.tron.common.runtime.vm;

import static org.tron.common.crypto.Hash.sha3;
import static org.tron.common.runtime.utils.MUtil.convertToTronAddress;
import static org.tron.common.runtime.vm.OpCode.CALL;
import static org.tron.common.runtime.vm.OpCode.PUSH1;
import static org.tron.common.runtime.vm.OpCode.REVERT;
import static org.tron.common.utils.ByteUtil.EMPTY_BYTE_ARRAY;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.tron.common.runtime.config.SystemProperties;
import org.tron.common.runtime.vm.program.Program;
import org.tron.common.runtime.vm.program.Stack;

public class VM {

    private static final Logger logger = LoggerFactory.getLogger("TronVM");
    private static final Logger dumpLogger = LoggerFactory.getLogger("dump");
    private static BigInteger _32_ = BigInteger.valueOf(32);
    private static final String logString = "{}    Op: [{}]  Gas: [{}] Deep: [{}]  Hint: [{}]";

    // max mem size which couldn't be paid for ever
    // used to reduce expensive BigInt arithmetic
    private static BigInteger MAX_MEM_SIZE = BigInteger.valueOf(Integer.MAX_VALUE);


    /* Keeps track of the number of steps performed in this VM */
    private int vmCounter = 0;
    private boolean vmTrace;
    // private long dumpBlock;

    private final SystemProperties config;

    public VM() {
        this(SystemProperties.getDefault());
    }

    @Autowired
    public VM(SystemProperties config) {
        this.config = config;
        // vmTrace = config.vmTrace();
        // dumpBlock = config.dumpBlock();
    }

    private long calcMemDrop(DropCost dropCosts, long oldMemSize, BigInteger newMemSize, long copySize) {
        long dropConsume = 0;

        // Avoid overflows
        if (newMemSize.compareTo(MAX_MEM_SIZE) > 0) {
            throw Program.Exception.gasOverflow(newMemSize, MAX_MEM_SIZE);
        }

        // memory drop consume calc
        long memoryUsage = (newMemSize.longValue() + 31) / 32 * 32;
        if (memoryUsage > oldMemSize) {
            long memWords = (memoryUsage / 32);
            long memWordsOld = (oldMemSize / 32);
            //TODO #POC9 c_quadCoeffDiv = 512, this should be a constant, not magic number
            long memDrop = ( dropCosts.getMEMORY() * memWords + memWords * memWords / 512)
                    - (dropCosts.getMEMORY() * memWordsOld + memWordsOld * memWordsOld / 512);
            dropConsume += memDrop;
        }

        if (copySize > 0) {
            long copyDrop = dropCosts.getCOPY_GAS() * ((copySize + 31) / 32);
            dropConsume += copyDrop;
        }
        return dropConsume;
    }

    public void step(Program program) {
        if (vmTrace) {
            program.saveOpTrace();
        }

        try {
            OpCode op = OpCode.code(program.getCurrentOp());
            if (op == null) throw Program.Exception.invalidOpCode(program.getCurrentOp());
            switch (op) {
                case DELEGATECALL:
                    // opcode since Homestead release only
                    //if (!tronConfig.getConstants().hasDelegateCallOpcode()) throw Program.Exception.invalidOpCode(program.getCurrentOp());
                    break;
                case REVERT:
                    //if (!tronConfig.eip206()) {
                    //    throw Program.Exception.invalidOpCode(program.getCurrentOp());
                    //}
                    break;
                case RETURNDATACOPY:
                case RETURNDATASIZE:
                    //if (!blockchainConfig.eip211()) {
                    //    throw Program.Exception.invalidOpCode(program.getCurrentOp());
                    //}
                    break;
                case STATICCALL:
                    //if (!blockchainConfig.eip214()) {
                    //    throw Program.Exception.invalidOpCode(program.getCurrentOp());
                    //}
                    break;
            }

            program.setLastOp(op.val());
            program.verifyStackSize(op.require());
            program.verifyStackOverflow(op.require(), op.ret()); //Check not exceeding stack limits

            long oldMemSize = program.getMemSize();
            Stack stack = program.getStack();

            String hint = "";
            long callGas = 0, memWords = 0; // parameters for logging
            long dropCost = op.getTier().asInt();
            long dropBefore = program.getDroplimitLong();
            int stepBefore = program.getPC();
            DropCost dropCosts = DropCost.getInstance();
            DataWord adjustedCallGas = null;

            // Calculate fees and spend drops
            switch (op) {
                case STOP:
                    dropCost = dropCosts.getSTOP();
                    break;
                case SUICIDE:
                    dropCost = dropCosts.getSUICIDE();
                    break;
                case SSTORE:
                    DataWord newValue = stack.get(stack.size() - 2);
                    DataWord oldValue = program.storageLoad(stack.peek());
                    if (oldValue == null && !newValue.isZero())
                        dropCost = dropCosts.getSET_SSTORE();
                    else if (oldValue != null && newValue.isZero()) {
                        // todo: GASREFUND counter policy

                        // refund step cost policy.
                        program.futureRefundGas(dropCosts.getREFUND_SSTORE());
                        dropCost = dropCosts.getCLEAR_SSTORE();
                    } else
                        dropCost = dropCosts.getRESET_SSTORE();
                    break;
                case SLOAD:
                    dropCost = dropCosts.getSLOAD();
                    break;
                case BALANCE:
                    dropCost = dropCosts.getBALANCE();
                    break;

                // These all operate on memory and therefore potentially expand it:
                case MSTORE:
                    dropCost += calcMemDrop(dropCosts, oldMemSize, memNeeded(stack.peek(), new DataWord(32)), 0);
                    break;
                case MSTORE8:
                    dropCost += calcMemDrop(dropCosts, oldMemSize, memNeeded(stack.peek(), new DataWord(1)), 0);
                    break;
                case MLOAD:
                    dropCost += calcMemDrop(dropCosts, oldMemSize, memNeeded(stack.peek(), new DataWord(32)), 0);
                    break;
                case RETURN:
                case REVERT:
                    dropCost = dropCosts.getSTOP() + calcMemDrop(dropCosts, oldMemSize,
                            memNeeded(stack.peek(), stack.get(stack.size() - 2)), 0);
                    break;
                case SHA3:
                    dropCost = dropCosts.getSHA3() + calcMemDrop(dropCosts, oldMemSize, memNeeded(stack.peek(), stack.get(stack.size() - 2)), 0);
                    DataWord size = stack.get(stack.size() - 2);
                    long chunkUsed = (size.longValueSafe() + 31) / 32;
                    dropCost += chunkUsed * dropCosts.getSHA3_WORD();
                    break;
                case CALLDATACOPY:
                case RETURNDATACOPY:
                    dropCost += calcMemDrop(dropCosts, oldMemSize,
                            memNeeded(stack.peek(), stack.get(stack.size() - 3)),
                            stack.get(stack.size() - 3).longValueSafe());
                    break;
                case CODECOPY:
                    dropCost += calcMemDrop(dropCosts, oldMemSize,
                            memNeeded(stack.peek(), stack.get(stack.size() - 3)),
                            stack.get(stack.size() - 3).longValueSafe());
                    break;
                case EXTCODESIZE:
                    dropCost = dropCosts.getEXT_CODE_SIZE();
                    break;
                case EXTCODECOPY:
                    dropCost = dropCosts.getEXT_CODE_COPY() + calcMemDrop(dropCosts, oldMemSize,
                            memNeeded(stack.get(stack.size() - 2), stack.get(stack.size() - 4)),
                            stack.get(stack.size() - 4).longValueSafe());
                    break;
                case CALL:
                case CALLCODE:
                case DELEGATECALL:
                case STATICCALL:

                    dropCost = dropCosts.getCALL();
                    DataWord callGasWord = stack.get(stack.size() - 1);

                    DataWord callAddressWord = stack.get(stack.size() - 2);

                    DataWord value = op.callHasValue() ?
                            stack.get(stack.size() - 3) : DataWord.ZERO;

                    //check to see if account does not exist and is not a precompiled contract
                    if (op == CALL) {
                        dropCost = dropCosts.getNEW_ACCT_CALL();
                    }

                    //TODO #POC9 Make sure this is converted to BigInteger (256num support)
                    if (!value.isZero() )
                        dropCost += dropCosts.getVT_CALL();

                    int opOff = op.callHasValue() ? 4 : 3;
                    BigInteger in = memNeeded(stack.get(stack.size() - opOff), stack.get(stack.size() - opOff - 1)); // in offset+size
                    BigInteger out = memNeeded(stack.get(stack.size() - opOff - 2), stack.get(stack.size() - opOff - 3)); // out offset+size
                    dropCost += calcMemDrop(dropCosts, oldMemSize, in.max(out), 0);

                    if (dropCost > program.getDroplimit().longValueSafe()) {
                        throw Program.Exception.notEnoughOpGas(op, callGasWord, program.getDroplimit());
                    }

                    DataWord gasLeft = program.getDroplimit().clone();
                    gasLeft.sub(new DataWord(dropCost));
                    //adjustedCallGas = tronConfig.getCallGas(op, callGasWord, gasLeft);
                    //adjustedCallGas = new DataWord(tronConfig.getDropCost().getCALL());
                    //TODO: remove below and recover above statment when config is ready
                    adjustedCallGas = new DataWord();
                    dropCost += adjustedCallGas.longValueSafe();
                    break;
                case CREATE:
                    dropCost = dropCosts.getCREATE() + calcMemDrop(dropCosts, oldMemSize,
                            memNeeded(stack.get(stack.size() - 2), stack.get(stack.size() - 3)), 0);
                    break;
                case LOG0:
                case LOG1:
                case LOG2:
                case LOG3:
                case LOG4:

                    int nTopics = op.val() - OpCode.LOG0.val();

                    BigInteger dataSize = stack.get(stack.size() - 2).value();
                    BigInteger dataCost = dataSize.multiply(BigInteger.valueOf(dropCosts.getLOG_DATA_GAS()));
                    if (program.getDroplimit().value().compareTo(dataCost) < 0) {
                        throw Program.Exception.notEnoughOpGas(op, dataCost, program.getDroplimit().value());
                    }

                    dropCost = dropCosts.getLOG_GAS() +
                            dropCosts.getLOG_TOPIC_GAS() * nTopics +
                            dropCosts.getLOG_DATA_GAS() * stack.get(stack.size() - 2).longValue() +
                            calcMemDrop(dropCosts, oldMemSize, memNeeded(stack.peek(), stack.get(stack.size() - 2)), 0);
                    break;
                case EXP:

                    DataWord exp = stack.get(stack.size() - 2);
                    int bytesOccupied = exp.bytesOccupied();
                    dropCost = dropCosts.getEXP_GAS() + dropCosts.getEXP_BYTE_GAS() * bytesOccupied;
                    break;
                default:
                    break;
            }

            //DEBUG System.out.println(" OP IS " + op.name() + " GASCOST IS " + gasCost + " NUM IS " + op.asInt());
            program.spendDrop(dropCost, op.name());


            // Execute operation
            switch (op) {
                /**
                 * Stop and Arithmetic Operations
                 */
                case STOP: {
                    program.setHReturn(EMPTY_BYTE_ARRAY);
                    program.stop();
                }
                break;
                case ADD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " + " + word2.value();

                    word1.add(word2);
                    program.stackPush(word1);
                    program.step();

                }
                break;
                case MUL: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " * " + word2.value();

                    word1.mul(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case SUB: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " - " + word2.value();

                    word1.sub(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case DIV: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " / " + word2.value();

                    word1.div(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case SDIV: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.sValue() + " / " + word2.sValue();

                    word1.sDiv(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case MOD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " % " + word2.value();

                    word1.mod(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case SMOD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.sValue() + " #% " + word2.sValue();

                    word1.sMod(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case EXP: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " ** " + word2.value();

                    word1.exp(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case SIGNEXTEND: {
                    DataWord word1 = program.stackPop();
                    BigInteger k = word1.value();

                    if (k.compareTo(_32_) < 0) {
                        DataWord word2 = program.stackPop();
                        if (logger.isInfoEnabled())
                            hint = word1 + "  " + word2.value();
                        word2.signExtend(k.byteValue());
                        program.stackPush(word2);
                    }
                    program.step();
                }
                break;
                case NOT: {
                    DataWord word1 = program.stackPop();
                    word1.bnot();

                    if (logger.isInfoEnabled())
                        hint = "" + word1.value();

                    program.stackPush(word1);
                    program.step();
                }
                break;
                case LT: {
                    // TODO: can be improved by not using BigInteger
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " < " + word2.value();

                    if (word1.value().compareTo(word2.value()) == -1) {
                        word1.and(DataWord.ZERO);
                        word1.getData()[31] = 1;
                    } else {
                        word1.and(DataWord.ZERO);
                    }
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case SLT: {
                    // TODO: can be improved by not using BigInteger
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.sValue() + " < " + word2.sValue();

                    if (word1.sValue().compareTo(word2.sValue()) == -1) {
                        word1.and(DataWord.ZERO);
                        word1.getData()[31] = 1;
                    } else {
                        word1.and(DataWord.ZERO);
                    }
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case SGT: {
                    // TODO: can be improved by not using BigInteger
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.sValue() + " > " + word2.sValue();

                    if (word1.sValue().compareTo(word2.sValue()) == 1) {
                        word1.and(DataWord.ZERO);
                        word1.getData()[31] = 1;
                    } else {
                        word1.and(DataWord.ZERO);
                    }
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case GT: {
                    // TODO: can be improved by not using BigInteger
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " > " + word2.value();

                    if (word1.value().compareTo(word2.value()) == 1) {
                        word1.and(DataWord.ZERO);
                        word1.getData()[31] = 1;
                    } else {
                        word1.and(DataWord.ZERO);
                    }
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case EQ: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " == " + word2.value();

                    if (word1.xor(word2).isZero()) {
                        word1.and(DataWord.ZERO);
                        word1.getData()[31] = 1;
                    } else {
                        word1.and(DataWord.ZERO);
                    }
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case ISZERO: {
                    DataWord word1 = program.stackPop();
                    if (word1.isZero()) {
                        word1.getData()[31] = 1;
                    } else {
                        word1.and(DataWord.ZERO);
                    }

                    if (logger.isInfoEnabled())
                        hint = "" + word1.value();

                    program.stackPush(word1);
                    program.step();
                }
                break;

                /**
                 * Bitwise Logic Operations
                 */
                case AND: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " && " + word2.value();

                    word1.and(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case OR: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " || " + word2.value();

                    word1.or(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case XOR: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " ^ " + word2.value();

                    word1.xor(word2);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case BYTE: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    final DataWord result;
                    if (word1.value().compareTo(_32_) == -1) {
                        byte tmp = word2.getData()[word1.intValue()];
                        word2.and(DataWord.ZERO);
                        word2.getData()[31] = tmp;
                        result = word2;
                    } else {
                        result = new DataWord();
                    }

                    if (logger.isInfoEnabled())
                        hint = "" + result.value();

                    program.stackPush(result);
                    program.step();
                }
                break;
                case ADDMOD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    DataWord word3 = program.stackPop();
                    word1.addmod(word2, word3);
                    program.stackPush(word1);
                    program.step();
                }
                break;
                case MULMOD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    DataWord word3 = program.stackPop();
                    word1.mulmod(word2, word3);
                    program.stackPush(word1);
                    program.step();
                }
                break;

                /**
                 * SHA3
                 */
                case SHA3: {
                    DataWord memOffsetData = program.stackPop();
                    DataWord lengthData = program.stackPop();
                    byte[] buffer = program.memoryChunk(memOffsetData.intValueSafe(), lengthData.intValueSafe());

                    byte[] encoded = sha3(buffer);
                    DataWord word = new DataWord(encoded);

                    if (logger.isInfoEnabled())
                        hint = word.toString();

                    program.stackPush(word);
                    program.step();
                }
                break;

                /**
                 * Environmental Information
                 */
                case ADDRESS: {
                    DataWord address = program.getOwnerAddress();

                    if (logger.isInfoEnabled())
                        hint = "address: " + Hex.toHexString(address.getLast20Bytes());

                  program.stackPush(address.getLast20Bytes());
                    program.step();
                }
                break;
                case BALANCE: {
                    DataWord address = program.stackPop();
                    DataWord balance = program.getBalance(address);

                    if (logger.isInfoEnabled())
                        hint = "address: "
                                + Hex.toHexString(address.getLast20Bytes())
                                + " balance: " + balance.toString();

                    program.stackPush(balance);
                    program.step();
                }
                break;
                case ORIGIN: {
                    DataWord originAddress = program.getOriginAddress();

                  originAddress = new DataWord((originAddress.getLast20Bytes()));
                    if (logger.isInfoEnabled())
                        hint = "address: " + Hex.toHexString(originAddress.getLast20Bytes());

                    program.stackPush(originAddress);
                    program.step();
                }
                break;
                case CALLER: {
                    DataWord callerAddress = program.getCallerAddress();
                    /**
                      since we use 21 bytes address instead of 20 as etherum, we need to make sure
                      the address length in vm is matching with 20
                     */
                    callerAddress = new DataWord(callerAddress.getLast20Bytes());
                    if (logger.isInfoEnabled())
                        hint = "address: " + Hex.toHexString(callerAddress.getLast20Bytes());

                    program.stackPush(callerAddress);
                    program.step();
                }
                break;
                case CALLVALUE: {
                    DataWord callValue = program.getCallValue();

                    if (logger.isInfoEnabled())
                        hint = "value: " + callValue;

                    program.stackPush(callValue);
                    program.step();
                }
                break;
                case CALLDATALOAD: {
                    DataWord dataOffs = program.stackPop();
                    DataWord value = program.getDataValue(dataOffs);

                    if (logger.isInfoEnabled())
                        hint = "data: " + value;

                    program.stackPush(value);
                    program.step();
                }
                break;
                case CALLDATASIZE: {
                    DataWord dataSize = program.getDataSize();

                    if (logger.isInfoEnabled())
                        hint = "size: " + dataSize.value();

                    program.stackPush(dataSize);
                    program.step();
                }
                break;
                case CALLDATACOPY: {
                    DataWord memOffsetData = program.stackPop();
                    DataWord dataOffsetData = program.stackPop();
                    DataWord lengthData = program.stackPop();

                    byte[] msgData = program.getDataCopy(dataOffsetData, lengthData);

                    if (logger.isInfoEnabled())
                        hint = "data: " + Hex.toHexString(msgData);

                    program.memorySave(memOffsetData.intValueSafe(), msgData);
                    program.step();
                }
                break;
                case RETURNDATASIZE: {
                    DataWord dataSize = program.getReturnDataBufferSize();

                    if (logger.isInfoEnabled())
                        hint = "size: " + dataSize.value();

                    program.stackPush(dataSize);
                    program.step();
                }
                break;
                case RETURNDATACOPY: {
                    DataWord memOffsetData = program.stackPop();
                    DataWord dataOffsetData = program.stackPop();
                    DataWord lengthData = program.stackPop();

                    byte[] msgData = program.getReturnDataBufferData(dataOffsetData, lengthData);

                    if (msgData == null) {
                        throw new Program.ReturnDataCopyIllegalBoundsException(dataOffsetData, lengthData, program.getReturnDataBufferSize().longValueSafe());
                    }

                    if (logger.isInfoEnabled())
                        hint = "data: " + Hex.toHexString(msgData);

                    program.memorySave(memOffsetData.intValueSafe(), msgData);
                    program.step();
                }
                break;
                case CODESIZE:
                case EXTCODESIZE: {

                    int length;
                    if (op == OpCode.CODESIZE)
                        length = program.getCode().length;
                    else {
                        DataWord address = program.stackPop();
                        length = program.getCodeAt(address).length;
                    }
                    DataWord codeLength = new DataWord(length);

                    if (logger.isInfoEnabled())
                        hint = "size: " + length;

                    program.stackPush(codeLength);
                    program.step();
                }
                break;
                case CODECOPY:
                case EXTCODECOPY: {

                    byte[] fullCode = EMPTY_BYTE_ARRAY;
                    if (op == OpCode.CODECOPY)
                        fullCode = program.getCode();

                    if (op == OpCode.EXTCODECOPY) {
                        DataWord address = program.stackPop();
                        fullCode = program.getCodeAt(address);
                    }

                    int memOffset = program.stackPop().intValueSafe();
                    int codeOffset = program.stackPop().intValueSafe();
                    int lengthData = program.stackPop().intValueSafe();

                    int sizeToBeCopied =
                            (long) codeOffset + lengthData > fullCode.length ?
                                    (fullCode.length < codeOffset ? 0 : fullCode.length - codeOffset)
                                    : lengthData;

                    byte[] codeCopy = new byte[lengthData];

                    if (codeOffset < fullCode.length)
                        System.arraycopy(fullCode, codeOffset, codeCopy, 0, sizeToBeCopied);

                    if (logger.isInfoEnabled())
                        hint = "code: " + Hex.toHexString(codeCopy);

                    program.memorySave(memOffset, codeCopy);
                    program.step();
                }
                break;
                case GASPRICE: {
                    DataWord gasPrice = program.getDropPrice();

                    if (logger.isInfoEnabled())
                        hint = "price: " + gasPrice.toString();

                    program.stackPush(gasPrice);
                    program.step();
                }
                break;

                /**
                 * Block Information
                 */
                case BLOCKHASH: {

                    int blockIndex = program.stackPop().intValueSafe();

                    DataWord blockHash = program.getBlockHash(blockIndex);

                    if (logger.isInfoEnabled())
                        hint = "blockHash: " + blockHash;

                    program.stackPush(blockHash);
                    program.step();
                }
                break;
                case COINBASE: {
                    DataWord coinbase = program.getCoinbase();

                  coinbase = new DataWord(coinbase.getLast20Bytes());
                    if (logger.isInfoEnabled())
                        hint = "coinbase: " + Hex.toHexString(coinbase.getLast20Bytes());

                    program.stackPush(coinbase);
                    program.step();
                }
                break;
                case TIMESTAMP: {
                    DataWord timestamp = program.getTimestamp();

                    if (logger.isInfoEnabled())
                        hint = "timestamp: " + timestamp.value();

                    program.stackPush(timestamp);
                    program.step();
                }
                break;
                case NUMBER: {
                    DataWord number = program.getNumber();

                    if (logger.isInfoEnabled())
                        hint = "number: " + number.value();

                    program.stackPush(number);
                    program.step();
                }
                break;
                case DIFFICULTY: {
                    DataWord difficulty = program.getDifficulty();

                    if (logger.isInfoEnabled())
                        hint = "difficulty: " + difficulty;

                    program.stackPush(difficulty);
                    program.step();
                }
                break;
                case GASLIMIT: {
                    DataWord droplimit = program.getDroplimit();

                    if (logger.isInfoEnabled())
                        hint = "gaslimit: " + droplimit;

                    program.stackPush(droplimit);
                    program.step();
                }
                break;
                case POP: {
                    program.stackPop();
                    program.step();
                }   break;
                case DUP1: case DUP2: case DUP3: case DUP4:
                case DUP5: case DUP6: case DUP7: case DUP8:
                case DUP9: case DUP10: case DUP11: case DUP12:
                case DUP13: case DUP14: case DUP15: case DUP16:{

                    int n = op.val() - OpCode.DUP1.val() + 1;
                    DataWord word_1 = stack.get(stack.size() - n);
                    program.stackPush(word_1.clone());
                    program.step();

                }   break;
                case SWAP1: case SWAP2: case SWAP3: case SWAP4:
                case SWAP5: case SWAP6: case SWAP7: case SWAP8:
                case SWAP9: case SWAP10: case SWAP11: case SWAP12:
                case SWAP13: case SWAP14: case SWAP15: case SWAP16:{

                    int n = op.val() - OpCode.SWAP1.val() + 2;
                    stack.swap(stack.size() - 1, stack.size() - n);
                    program.step();
                }
                break;
                case LOG0:
                case LOG1:
                case LOG2:
                case LOG3:
                case LOG4: {

                    if (program.isStaticCall()) throw new Program.StaticCallModificationException();
                    DataWord address = program.getOwnerAddress();

                    DataWord memStart = stack.pop();
                    DataWord memOffset = stack.pop();

                    int nTopics = op.val() - OpCode.LOG0.val();

                    List<DataWord> topics = new ArrayList<>();
                    for (int i = 0; i < nTopics; ++i) {
                        DataWord topic = stack.pop();
                        topics.add(topic);
                    }

                    byte[] data = program.memoryChunk(memStart.intValueSafe(), memOffset.intValueSafe());

                    LogInfo logInfo =
                        new LogInfo(convertToTronAddress(address.getLast20Bytes()), topics, data);

                    if (logger.isInfoEnabled())
                        hint = logInfo.toString();

                    program.getResult().addLogInfo(logInfo);
                    program.step();
                }
                break;
                case MLOAD: {
                    DataWord addr = program.stackPop();
                    DataWord data = program.memoryLoad(addr);

                    if (logger.isInfoEnabled())
                        hint = "data: " + data;

                    program.stackPush(data);
                    program.step();
                }
                break;
                case MSTORE: {
                    DataWord addr = program.stackPop();
                    DataWord value = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = "addr: " + addr + " value: " + value;

                    program.memorySave(addr, value);
                    program.step();
                }
                break;
                case MSTORE8: {
                    DataWord addr = program.stackPop();
                    DataWord value = program.stackPop();
                    byte[] byteVal = {value.getData()[31]};
                    program.memorySave(addr.intValueSafe(), byteVal);
                    program.step();
                }
                break;
                case SLOAD: {
                    DataWord key = program.stackPop();
                    DataWord val = program.storageLoad(key);

                    if (logger.isInfoEnabled())
                        hint = "key: " + key + " value: " + val;

                    if (val == null)
                        val = key.and(DataWord.ZERO);

                    program.stackPush(val);
                    program.step();
                }
                break;
                case SSTORE: {
                    if (program.isStaticCall()) throw new Program.StaticCallModificationException();

                    DataWord addr = program.stackPop();
                    DataWord value = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = "[" + program.getOwnerAddress().toPrefixString() + "] key: " + addr + " value: " + value;

                    program.storageSave(addr, value);
                    program.step();
                }
                break;
                case JUMP: {
                    DataWord pos = program.stackPop();
                    int nextPC = program.verifyJumpDest(pos);

                    if (logger.isInfoEnabled())
                        hint = "~> " + nextPC;

                    program.setPC(nextPC);

                }
                break;
                case JUMPI: {
                    DataWord pos = program.stackPop();
                    DataWord cond = program.stackPop();

                    if (!cond.isZero()) {
                        int nextPC = program.verifyJumpDest(pos);

                        if (logger.isInfoEnabled())
                            hint = "~> " + nextPC;

                        program.setPC(nextPC);
                    } else {
                        program.step();
                    }

                }
                break;
                case PC: {
                    int pc = program.getPC();
                    DataWord pcWord = new DataWord(pc);

                    if (logger.isInfoEnabled())
                        hint = pcWord.toString();

                    program.stackPush(pcWord);
                    program.step();
                }
                break;
                case MSIZE: {
                    int memSize = program.getMemSize();
                    DataWord wordMemSize = new DataWord(memSize);

                    if (logger.isInfoEnabled())
                        hint = "" + memSize;

                    program.stackPush(wordMemSize);
                    program.step();
                }
                break;
                case GAS: {
                    DataWord gas = program.getDroplimit();

                    if (logger.isInfoEnabled())
                        hint = "" + gas;

                    program.stackPush(gas);
                    program.step();
                }
                break;

                case PUSH1:
                case PUSH2:
                case PUSH3:
                case PUSH4:
                case PUSH5:
                case PUSH6:
                case PUSH7:
                case PUSH8:
                case PUSH9:
                case PUSH10:
                case PUSH11:
                case PUSH12:
                case PUSH13:
                case PUSH14:
                case PUSH15:
                case PUSH16:
                case PUSH17:
                case PUSH18:
                case PUSH19:
                case PUSH20:
                case PUSH21:
                case PUSH22:
                case PUSH23:
                case PUSH24:
                case PUSH25:
                case PUSH26:
                case PUSH27:
                case PUSH28:
                case PUSH29:
                case PUSH30:
                case PUSH31:
                case PUSH32: {
                    program.step();
                    int nPush = op.val() - PUSH1.val() + 1;

                    byte[] data = program.sweep(nPush);

                    if (logger.isInfoEnabled())
                        hint = "" + Hex.toHexString(data);

                    program.stackPush(data);
                }
                break;
                case JUMPDEST: {
                    program.step();
                }
                break;
                case CREATE: {
                    if (program.isStaticCall()) throw new Program.StaticCallModificationException();

                    DataWord value = program.stackPop();
                    DataWord inOffset = program.stackPop();
                    DataWord inSize = program.stackPop();

                    /*
                    if (logger.isInfoEnabled())
                        logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                                String.format("%-12s", op.name()),
                                program.getDroplimit().value(),
                                program.getCallDeep(), hint);
                    */
                    program.createContract(value, inOffset, inSize);

                    program.step();
                }
                break;
                case CALL:
                case CALLCODE:
                case DELEGATECALL:
                case STATICCALL: {
                    program.stackPop(); // use adjustedCallGas instead of requested
                    DataWord codeAddress = program.stackPop();
                    DataWord value = op.callHasValue() ?
                            program.stackPop() : DataWord.ZERO;

                    if (program.isStaticCall() && op == CALL && !value.isZero())
                        throw new Program.StaticCallModificationException();

                    if( !value.isZero()) {
                        adjustedCallGas.add(new DataWord(dropCosts.getSTIPEND_CALL()));
                    }

                    DataWord inDataOffs = program.stackPop();
                    DataWord inDataSize = program.stackPop();

                    DataWord outDataOffs = program.stackPop();
                    DataWord outDataSize = program.stackPop();

                    if (logger.isInfoEnabled()) {
                        hint = "addr: " + Hex.toHexString(codeAddress.getLast20Bytes())
                                + " gas: " + adjustedCallGas.shortHex()
                                + " inOff: " + inDataOffs.shortHex()
                                + " inSize: " + inDataSize.shortHex();
                        logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                                String.format("%-12s", op.name()),
                                program.getDroplimit().value(),
                                program.getCallDeep(), hint);
                    }

                    program.memoryExpand(outDataOffs, outDataSize);

                    MessageCall msg = new MessageCall(
                            op, adjustedCallGas, codeAddress, value, inDataOffs, inDataSize,
                            outDataOffs, outDataSize);

                    PrecompiledContracts.PrecompiledContract contract =
                            PrecompiledContracts.getContractForAddress(codeAddress);

                    if (!op.callIsStateless()) {
                        program.getResult().addTouchAccount(codeAddress.getLast20Bytes());
                    }

                    if (contract != null) {
                        program.callToPrecompiledAddress(msg, contract);
                    } else {
                        program.callToAddress(msg);
                    }

                    program.step();
                }
                break;
                case RETURN:
                case REVERT: {
                    DataWord offset = program.stackPop();
                    DataWord size = program.stackPop();

                    byte[] hReturn = program.memoryChunk(offset.intValueSafe(), size.intValueSafe());
                    program.setHReturn(hReturn);

                    if (logger.isInfoEnabled())
                        hint = "data: " + Hex.toHexString(hReturn)
                                + " offset: " + offset.value()
                                + " size: " + size.value();

                    program.step();
                    program.stop();

                    if (op == REVERT) {
                        program.getResult().setRevert();
                    }
                }
                break;
                case SUICIDE: {
                    if (program.isStaticCall()) throw new Program.StaticCallModificationException();

                    DataWord address = program.stackPop();
                    program.suicide(address);
                  // TODO account length
                    program.getResult().addTouchAccount(address.getLast20Bytes());

                    if (logger.isInfoEnabled())
                        hint = "address: " + Hex.toHexString(program.getOwnerAddress().getLast20Bytes());

                    program.stop();
                }
                break;
                default:
                    break;
            }

            program.setPreviouslyExecutedOp(op.val());
            /*
            if (logger.isInfoEnabled() && !op.isCall())
                logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                        String.format("%-12s",
                                op.name()), program.getDroplimit().value(),
                        program.getCallDeep(), hint);
            */
            vmCounter++;
        } catch (RuntimeException e) {
            logger.warn("VM halted: [{}]", e);
            program.spendAllGas();
            program.resetFutureRefund();
            program.stop();
            throw e;
        } finally {
            program.fullTrace();
        }
    }

    public void play(Program program) {
        try {
            if (program.byTestingSuite()) return;

            while (!program.isStopped()) {
                this.step(program);
            }

        } catch (RuntimeException e) {
            program.setRuntimeFailure(e);
        } catch (StackOverflowError soe){
            logger.error("\n !!! StackOverflowError: update your java run command with -Xss2M !!!\n", soe);
            System.exit(-1);
        } finally {

        }
    }


    /**
     * Utility to calculate new total memory size needed for an operation.
     * <br/> Basically just offset + size, unless size is 0, in which case the result is also 0.
     *
     * @param offset starting position of the memory
     * @param size number of bytes needed
     * @return offset + size, unless size is 0. In that case memNeeded is also 0.
     */
    private static BigInteger memNeeded(DataWord offset, DataWord size) {
        return size.isZero() ? BigInteger.ZERO : offset.value().add(size.value());
    }
}
