// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model._
import org.alephium.util.{discard, AVector}

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait StatelessContext extends CostStrategy {
  def txId: Hash
  def signatures: Stack[Signature]
  def getInitialBalances(): ExeResult[Balances]
}

object StatelessContext {
  def apply(txId: Hash, txGas: GasBox, signature: Signature): StatelessContext = {
    val stack = Stack.unsafe[Signature](mutable.ArraySeq(signature), 1)
    apply(txId, txGas, stack)
  }

  def apply(txId: Hash, txGas: GasBox, signatures: Stack[Signature]): StatelessContext =
    new Impl(txId, signatures, txGas)

  final class Impl(val txId: Hash, val signatures: Stack[Signature], var gasRemaining: GasBox)
      extends StatelessContext {
    override def getInitialBalances(): ExeResult[Balances] = failed(NonPayableFrame)
  }
}

trait StatefulContext extends StatelessContext with ContractPool {
  def worldState: WorldState.Staging

  def outputBalances: Balances

  lazy val generatedOutputs: ArrayBuffer[TxOutput]        = ArrayBuffer.empty
  lazy val contractInputs: ArrayBuffer[ContractOutputRef] = ArrayBuffer.empty

  def nextOutputIndex: Int

  def nextContractOutputRef(output: ContractOutput): ContractOutputRef =
    ContractOutputRef.unsafe(txId, output, nextOutputIndex)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def generateOutput(output: TxOutput): ExeResult[Unit] = {
    output.lockupScript match {
      case LockupScript.P2C(contractId) =>
        val contractOutput = output.asInstanceOf[ContractOutput]
        val outputRef      = nextContractOutputRef(contractOutput)
        generatedOutputs.addOne(output)
        updateContractAsset(contractId, outputRef, contractOutput)
      case _ =>
        generatedOutputs.addOne(output)
        Right(())
    }
  }

  def createContract(
      code: StatefulContract,
      initialBalances: BalancesPerLockup,
      initialFields: AVector[Val]
  ): ExeResult[Unit] = {
    val contractId = TxOutputRef.key(txId, nextOutputIndex)
    val contractOutput = ContractOutput(
      initialBalances.alfAmount,
      LockupScript.p2c(contractId),
      initialBalances.tokenVector
    )
    val outputRef = nextContractOutputRef(contractOutput)
    for {
      _ <- code.check(initialFields)
      _ <-
        worldState
          .createContractUnsafe(code, initialFields, outputRef, contractOutput)
          .map(_ => discard(generatedOutputs.addOne(contractOutput)))
          .left
          .map(e => Left(IOErrorUpdateState(e)))
    } yield ()
  }

  def useContractAsset(contractId: ContractId): ExeResult[BalancesPerLockup] = {
    for {
      balances <- worldState
        .useContractAsset(contractId)
        .map { case (contractOutputRef, contractAsset) =>
          contractInputs.addOne(contractOutputRef)
          BalancesPerLockup.from(contractAsset)
        }
        .left
        .map(e => Left(IOErrorLoadContract(e)))
      _ <- markAssetInUsing(contractId)
    } yield balances
  }

  def updateContractAsset(
      contractId: ContractId,
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): ExeResult[Unit] = {
    for {
      _ <- worldState
        .updateContract(contractId, outputRef, output)
        .left
        .map(e => Left(IOErrorUpdateState(e)))
      _ <- markAssetFlushed(contractId)
    } yield ()
  }
}

object StatefulContext {
  def apply(
      tx: TransactionAbstract,
      gasRemaining: GasBox,
      worldState: WorldState.Cached,
      preOutputs: AVector[TxOutput]
  ): StatefulContext = {
    new Impl(tx, worldState, preOutputs, gasRemaining)
  }

  def build(
      tx: TransactionAbstract,
      gasRemaining: GasBox,
      worldState: WorldState.Cached,
      preOutputsOpt: Option[AVector[TxOutput]]
  ): ExeResult[StatefulContext] = {
    preOutputsOpt match {
      case Some(outputs) => Right(apply(tx, gasRemaining, worldState, outputs))
      case None =>
        worldState.getPreOutputsForVM(tx) match {
          case Right(Some(outputs)) => Right(apply(tx, gasRemaining, worldState, outputs))
          case Right(None)          => failed(NonExistTxInput)
          case Left(error)          => ioFailed(IOErrorLoadOutputs(error))
        }
    }
  }

  final class Impl(
      val tx: TransactionAbstract,
      val initWorldState: WorldState.Cached,
      val preOutputs: AVector[TxOutput],
      var gasRemaining: GasBox
  ) extends StatefulContext {
    override val worldState: WorldState.Staging = initWorldState.staging()

    override def txId: Hash = tx.id

    override val signatures: Stack[Signature] = Stack.popOnly(tx.contractSignatures)

    override def nextOutputIndex: Int = tx.unsigned.fixedOutputs.length + generatedOutputs.length

    /*
     * this should be used only when the tx has passed these checks in validation
     * 1. inputs are not empty
     * 2. gas fee bounds are validated
     */
    @SuppressWarnings(
      Array(
        "org.wartremover.warts.JavaSerializable",
        "org.wartremover.warts.Product",
        "org.wartremover.warts.Serializable"
      )
    )
    override def getInitialBalances(): ExeResult[Balances] =
      if (tx.unsigned.scriptOpt.exists(_.entryMethod.isPayable)) {
        for {
          balances <- Balances
            .from(preOutputs, tx.unsigned.fixedOutputs)
            .toRight(Right(InvalidBalances))
          _ <- balances
            .subAlf(preOutputs.head.lockupScript, tx.gasFeeUnsafe)
            .toRight(Right(UnableToPayGasFee))
        } yield balances
      } else {
        failed(NonPayableFrame)
      }

    override val outputBalances: Balances = Balances.empty
  }
}
