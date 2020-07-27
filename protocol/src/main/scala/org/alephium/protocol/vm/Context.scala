package org.alephium.protocol.vm

import scala.collection.mutable.ArrayBuffer

import org.alephium.crypto.ED25519Signature
import org.alephium.protocol.ALF.Hash
import org.alephium.util.AVector

trait ChainEnv
trait BlockEnv
trait TxEnv
trait ContractEnv

trait Context {
  def txHash: Hash
  def signatures: Stack[ED25519Signature]
  def worldState: WorldState

  def updateWorldState(newWorldState: WorldState): Unit

  def updateState(key: Hash, state: AVector[Val]): ExeResult[Unit] = {
    worldState.putContractState(key, state) match {
      case Left(error) =>
        Left(IOErrorUpdateState(error))
      case Right(state) =>
        updateWorldState(state)
        Right(())
    }
  }
}

class StatelessContext(val txHash: Hash,
                       val signatures: Stack[ED25519Signature],
                       var worldState: WorldState)
    extends Context {
  override def updateWorldState(newWorldState: WorldState): Unit = worldState = newWorldState
}

object StatelessContext {
  def apply(txHash: Hash, signature: ED25519Signature, worldState: WorldState): StatelessContext = {
    val stack = Stack.unsafe[ED25519Signature](ArrayBuffer(signature), 1)
    apply(txHash, stack, worldState)
  }

  def apply(txHash: Hash, worldState: WorldState): StatelessContext =
    apply(txHash, Stack.ofCapacity[ED25519Signature](0), worldState)

  def apply(txHash: Hash,
            signatures: Stack[ED25519Signature],
            worldState: WorldState): StatelessContext =
    new StatelessContext(txHash, signatures, worldState)
}

class StatefulContext(override val txHash: Hash, private val _worldState: WorldState)
    extends StatelessContext(txHash, Stack.ofCapacity(0), _worldState)

object StatefulContext {
  def apply(txHash: Hash, worldState: WorldState): StatefulContext =
    new StatefulContext(txHash, worldState)
}