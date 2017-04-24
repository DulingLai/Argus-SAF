/*
 * Copyright (c) 2017. Fengguo Wei and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Detailed contributors are listed in the CONTRIBUTOR.md
 */

package org.argus.jawa.alir.controlFlowGraph

import org.argus.jawa.alir._
import org.argus.jawa.compiler.parser._
import org.argus.jawa.core.util._
import org.jgrapht.ext.ComponentNameProvider

trait ControlFlowGraph[N <: AlirNode]
    extends AlirGraphImpl[N]
    with AlirSuccPredAccesses[N]
    with AlirEdgeAccesses[N] {

  def entryNode: N
  def exitNode: N
  def reverse: ControlFlowGraph[N]
}

abstract class IntraProceduralControlFlowGraph[N <: CFGNode]
    extends ControlFlowGraph[N] {

  def getNode(locUri: ResourceUri, locIndex: Int): N =
    pool(newNode(locUri, locIndex))

  def getNode(l: Location): N =
    getNode(l.locationUri, l.locationIndex)

  def getVirtualNode(vlabel: String): N =
    pool(newVirtualNode(vlabel))

  protected def newNode(locUri: ResourceUri, locIndex: Int): CFGLocationNode =
    CFGLocationNode(locUri, locIndex)

  protected def newVirtualNode(vlabel: String) =
    CFGVirtualNode(vlabel)

  def addNode(locUri: ResourceUri, locIndex: Int): N = {
    val node = newNode(locUri, locIndex).asInstanceOf[N]
    val n =
      if (pool.contains(node)) pool(node)
      else {
        pool(node) = node
        node
      }
    graph.addVertex(n)
    n
  }

  def addVirtualNode(vlabel: String): N = {
    val node = newVirtualNode(vlabel).asInstanceOf[N]
    val n =
      if (pool.contains(node)) pool(node)
      else {
        pool(node) = node
        node
      }
    graph.addVertex(n)
    n
  }

  override protected val vIDProvider = new ComponentNameProvider[N]() {
    def filterLabel(uri: String): String = uri.filter(_.isUnicodeIdentifierPart) // filters out the special characters like '/', '.', '%', etc.
    def getName(v: N): String = {
      val str = v match {
        case CFGLocationNode(locUri, _) => UriUtil.lastPath(locUri)
        case CFGVirtualNode(vlabel)        => vlabel.toString
      }
      filterLabel(str)
    }
  }
}

trait CFGNode extends AlirNode

/**
  * @author <a href="mailto:robby@k-state.edu">Robby</a>
  * @author <a href="mailto:fgwei521@gmail.com">Fengguo Wei</a>
  */
final case class CFGLocationNode(locUri: ResourceUri, locIndex: Int) extends CFGNode with AlirLoc {
  override def toString: String = locUri
}

/**
  * @author <a href="mailto:robby@k-state.edu">Robby</a>
  */
final case class CFGVirtualNode(label: String) extends CFGNode {
  override def toString: String = label.toString
}

object ControlFlowGraph {
  type Node = CFGNode
  type ShouldIncludeFlowFunction = (Location, Iterable[CatchClause]) => (Iterable[CatchClause], Boolean)

  val defaultSiff: ShouldIncludeFlowFunction = { (_, catchClauses) =>
    (catchClauses, false)
  }
  
  def apply(
    body: Body,
    entryLabel: String, exitLabel: String,
    shouldIncludeFlow: ShouldIncludeFlowFunction = defaultSiff): IntraProceduralControlFlowGraph[Node] = build(body, entryLabel, exitLabel, shouldIncludeFlow)

  def build(
    body: Body,
    entryLabel: String, exitLabel: String,
    shouldIncludeFlow: ShouldIncludeFlowFunction): IntraProceduralControlFlowGraph[Node] = {

    val resolvedBody = body match {
      case rb: ResolvedBody => rb
      case ub: UnresolvedBody => ub.resolve
    }
    val locationDecls = resolvedBody.locations
    val result = new Cfg()
    if (locationDecls.isEmpty) return result

    def getLocUriIndex(l: Location) =
      (l.locationUri, l.locationIndex)

    def getNode(l: Location) =
      result.getNode(l.locationUri, l.locationIndex)

    val verticesMap = mmapEmpty[ResourceUri, Node]
    for (ld <- locationDecls) {
      val lui = getLocUriIndex(ld)
      val n = result.addNode(lui._1, lui._2)
      verticesMap(lui._1) = n
    }

    val exitNode = result.addVirtualNode(exitLabel)
    result.entryNode = result.addVirtualNode(entryLabel)
    result.addEdge(result.entryNode, getNode(locationDecls.head))
    result.exitNode = exitNode
    var source: Node = null
    var next: Node = null
    
    val size = locationDecls.size
    for (i <- 0 until size) {
      val l = locationDecls(i)
      source = getNode(l)
      next = if (i != size - 1) getNode(locationDecls(i + 1)) else exitNode
      l.statement match {
        case _: CallStatement =>
          result.addEdge(source, next)
        case _: AssignmentStatement =>
          result.addEdge(source, next)
        case _: ThrowStatement =>
          result.addEdge(source, exitNode)
        case is: IfStatement =>
          result.addEdge(source, next)
          next = verticesMap.getOrElse(is.targetLocation.location, exitNode)
          result.addEdge(source, next)
        case gs: GotoStatement =>
          next = verticesMap.getOrElse(gs.targetLocation.location, exitNode)
          result.addEdge(source, next)
        case ss: SwitchStatement =>
          ss.cases foreach {
            c =>
              next = verticesMap.getOrElse(c.targetLocation.location, exitNode)
              result.addEdge(source, next)
          }
          ss.defaultCaseOpt match {
            case Some(d) =>
              next = verticesMap.getOrElse(d.targetLocation.location, exitNode)
              result.addEdge(source, next)
            case None => result.addEdge(source, next)
          }
        case _: ReturnStatement =>
          result.addEdge(source, exitNode)
        case _ =>
          result.addEdge(source, next)
      }
      val (ccs, toExit) = shouldIncludeFlow(l, resolvedBody.getCatchClauses(l.locationSymbol.locationIndex))
      ccs.foreach { cc =>
        result.addEdge(source, verticesMap.getOrElse(cc.targetLocation.location, exitNode))
      }
      if (toExit) result.addEdge(source, exitNode)
    }
    result
  }

  private class Cfg
      extends IntraProceduralControlFlowGraph[Node] {

    var entryNode: Node = _

    var exitNode: Node = _

    def reverse: Cfg = {
      val result = new Cfg()
      for (n <- nodes) result.addNode(n)
      for (e <- edges) result.addEdge(e.target, e.source)
      result.entryNode = exitNode
      result.exitNode = entryNode
      result
    }

    override def toString: String = {
      val sb = new StringBuilder("CFG\n")
      for (n <- nodes)
        for (m <- successors(n)) {
          for (_ <- getEdges(n, m)) {
            sb.append(s"${n.toString} -> ${m.toString} %\n")
          }
        }
      sb.append("\n")
      sb.toString
    }
  }
}
