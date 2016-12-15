package scorex.crypto.authds.avltree

import scorex.crypto.authds._
import scorex.crypto.authds.TwoPartyDictionary.Label
import scorex.crypto.encode.Base58
import scorex.crypto.hash.{Blake2b256Unsafe, ThreadUnsafeHash}
import scorex.utils.ByteArray

import scala.util.{Failure, Success, Try}

class AVLTree[HF <: ThreadUnsafeHash](keyLength: Int, valueLength: Int = 8, rootOpt: Option[ProverNodes] = None)
                                     (implicit hf: HF = new Blake2b256Unsafe)
  extends TwoPartyDictionary[AVLKey, AVLValue, AVLModifyProof] {

  private val PositiveInfinityKey: Array[Byte] = Array.fill(keyLength)(-1: Byte)
  private val NegativeInfinityKey: Array[Byte] = Array.fill(keyLength)(0: Byte)

  var topNode: ProverNodes = rootOpt.getOrElse(Leaf(NegativeInfinityKey, Array.fill(valueLength)(0:Byte),
    PositiveInfinityKey))

  def rootHash(): Label = topNode.label

  override def modify(key: AVLKey, updateFunction: UpdateFunction): Try[AVLModifyProof] = Try {
    require(ByteArray.compare(key, NegativeInfinityKey) > 0, s"Key ${Base58.encode(key)} is less than -inf")
    require(ByteArray.compare(key, PositiveInfinityKey) < 0, s"Key ${Base58.encode(key)} is more than +inf")
    require(key.length == keyLength, s"Key length ${key.length} != $keyLength")

    val proofStream = new scala.collection.mutable.Queue[AVLProofElement]

    /**
      * foundAbove tells us if x has been already found above r in the tree
      * returns the new root and an indicator whether tree has been modified at r or below
      *
      */
    def modifyHelper(rNode: ProverNodes, foundAbove: Boolean): (ProverNodes, Boolean, Boolean) = {
      rNode match {
        case r: Leaf =>
          if (foundAbove) {
            // we already know it's in the tree, so it must be at the current leaf
            proofStream.enqueue(ProofDirection(LeafFound))
            proofStream.enqueue(ProofNextLeafKey(r.nextLeafKey))
            proofStream.enqueue(ProofValue(r.value))
            updateFunction(Some(r.value)) match {
              case Success(None) => //delete value
                ???
              case Success(Some(v)) => //update value
                require(v.length == valueLength)
                r.value = v
                (r, true, false)
              case Failure(e) => // found incorrect value
                throw e
            }
          } else {
            // x > r.key
            proofStream.enqueue(ProofDirection(LeafNotFound))
            proofStream.enqueue(ProofKey(r.key))
            proofStream.enqueue(ProofNextLeafKey(r.nextLeafKey))
            proofStream.enqueue(ProofValue(r.value))
            updateFunction(None) match {
              case Success(None) => //don't change anything, just lookup
                (r, false, false)
              case Success(Some(v)) => //insert new value
                require(v.length == valueLength)
                val newLeaf = new Leaf(key, v, r.nextLeafKey)
                r.nextLeafKey = key
                (ProverNode(key, r, newLeaf), true, true)
              case Failure(e) => // found incorrect value
                throw e
            }
          }
        case r: ProverNode =>
          // First figure out the direction in which we need to go
          val (nextStepIsLeft, found) = if (foundAbove) {
            // if it's already been found above, you always go left until leaf
            (true, true)
          } else {
            ByteArray.compare(key, r.key) match {
              case 0 => // found in the tree -- go one step right, then left to the leaf
                (false, true)
              case o if o < 0 => // going left
                (true, false)
              case _ => // going right
                (false, false)
            }
          }
          // Now go recursively in the direction we just figured out
          // Get a new node
          // See if a single or double rotation is needed for AVL tree balancing
          if (nextStepIsLeft) {
            proofStream.enqueue(ProofDirection(GoingLeft))
            proofStream.enqueue(ProofRightLabel(r.rightLabel))
            proofStream.enqueue(ProofBalance(r.balance))

            val (newLeftM, changeHappened, childHeightIncreased) = modifyHelper(r.left, found)

            // balance = -1 if left higher, +1 if left lower
            if (changeHappened) {
              if (childHeightIncreased && r.balance < 0) {
                // need to rotate
                newLeftM match {
                  // at this point we know newleftM must be an internal node an not a leaf -- b/c height increased;
                  case newLeft: ProverNode =>
                    if (newLeft.balance < 0) {
                      // single rotate
                      r.left = newLeft.right
                      r.balance = 0: Byte
                      newLeft.right = r
                      newLeft.balance = 0: Byte
                      assert(r.checkHeight)
                      assert(newLeft.checkHeight)
                      (newLeft, true, false)
                    } else {
                      // double rotate
                      val newRootM = newLeft.right
                      assert(newRootM.isInstanceOf[ProverNode])
                      val newRoot = newRootM.asInstanceOf[ProverNode]

                      assert(newLeft.balance > 0)

                      r.left = newRoot.right
                      newRoot.right = r
                      newLeft.right = newRoot.left
                      newRoot.left = newLeft

                      newRoot.balance match {
                        case 0 =>
                          // newRoot is a newly created node
                          assert(r.left.isInstanceOf[Leaf] && r.right.isInstanceOf[Leaf])
                          assert(newLeft.left.isInstanceOf[Leaf] && newLeft.right.isInstanceOf[Leaf])
                          newLeft.balance = 0: Byte
                          r.balance = 0: Byte
                        case -1 =>
                          newLeft.balance = 0: Byte
                          r.balance = 1: Byte
                        case 1 =>
                          newLeft.balance = -1: Byte
                          r.balance = 0: Byte
                      }
                      newRoot.balance = 0: Byte

                      assert(r.checkHeight)
                      assert(newLeft.checkHeight)
                      assert(newRoot.checkHeight)

                      (newRoot, true, false)
                    }
                  case newLeft =>
                    throw new Error("Got a leaf, internal node expected")
                }
              } else {
                // no need to rotate
                r.left = newLeftM
                val myHeightIncreased: Boolean = childHeightIncreased && r.balance == (0: Byte)
                if (childHeightIncreased) r.balance = (r.balance - 1).toByte
                assert(r.checkHeight)

                (r, true, myHeightIncreased)
              }

            } else {
              // no change happened
              assert(r.checkHeight)
              (r, false, false)
            }
          } else {
            // next step is to the right
            proofStream.enqueue(ProofDirection(GoingRight))
            proofStream.enqueue(ProofLeftLabel(r.leftLabel))
            proofStream.enqueue(ProofBalance(r.balance))
            val (newRightM, changeHappened, childHeightIncreased) = modifyHelper(r.right, found)

            if (changeHappened) {
              if (childHeightIncreased && r.balance > 0) {
                // need to rotate
                newRightM match {
                  // at this point we know newRightM must be an internal node and not a leaf -- because height increased
                  case newRight: ProverNode =>
                    if (newRight.balance > 0) {
                      // single rotate
                      r.right = newRight.left
                      r.balance = 0: Byte
                      newRight.left = r
                      newRight.balance = 0: Byte
                      assert(r.checkHeight)
                      assert(newRight.checkHeight)
                      (newRight, true, false)
                    } else {
                      // double rotate
                      val newRootM = newRight.left
                      assert(newRootM.isInstanceOf[ProverNode])
                      val newRoot = newRootM.asInstanceOf[ProverNode]

                      assert(newRight.balance < 0)

                      r.right = newRoot.left
                      newRoot.left = r
                      newRight.left = newRoot.right
                      newRoot.right = newRight

                      newRoot.balance match {
                        case 0 =>
                          // newRoot is an newly created node
                          assert(r.left.isInstanceOf[Leaf] && r.right.isInstanceOf[Leaf])
                          assert(newRight.left.isInstanceOf[Leaf] && newRight.right.isInstanceOf[Leaf])
                          newRight.balance = 0: Byte
                          r.balance = 0: Byte
                        case -1 =>
                          newRight.balance = 1: Byte
                          r.balance = 0: Byte
                        case 1 =>
                          newRight.balance = 0: Byte
                          r.balance = -1: Byte
                      }
                      newRoot.balance = 0: Byte

                      assert(r.checkHeight)
                      assert(newRight.checkHeight)
                      assert(newRoot.checkHeight)

                      (newRoot, true, false)
                    }
                  case newRight =>
                    throw new Error("Got a leaf, internal node expected")
                }
              } else {
                // no need to rotate
                r.right = newRightM
                val myHeightIncreased: Boolean = childHeightIncreased && r.balance == (0: Byte)
                if (childHeightIncreased) r.balance = (r.balance + 1).toByte
                assert(r.checkHeight)
                (r, true, myHeightIncreased)
              }
            } else {
              // no change happened
              assert(r.checkHeight)
              (r, false, false)
            }
          }
      }
    }

    val (newTopNode, changeHappened, childHeightIncreased) = modifyHelper(topNode, foundAbove = false)
    if (changeHappened) topNode = newTopNode
    AVLModifyProof(key, proofStream)
  }
}
