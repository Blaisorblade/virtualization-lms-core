package scala.virtualization.lms
package internal

import util.GraphUtil
import java.io.{File, PrintWriter}

trait GenericFatCodegen extends GenericNestedCodegen with FatScheduling {
  val IR: Expressions with Effects with FatExpressions
  import IR._  
  
  //  ------------------- these are needed by loop fusion. they should live elsewhere.
  def unapplySimpleIndex(e: Def[Any]): Option[(Exp[Any], Exp[Int])] = None
  def unapplySimpleDomain(e: Def[Int]): Option[Exp[Any]] = None
  def unapplySimpleCollect(e: Def[Any]): Option[Exp[Any]] = None
  def unapplySimpleCollectIf(e: Def[Any]): Option[(Exp[Any],List[Exp[Boolean]])] = unapplySimpleCollect(e).map((_,Nil))

  def applyAddCondition(e: Def[Any], c: List[Exp[Boolean]]): Def[Any] = sys.error("not implemented")

  def shouldApplyFusion(currentScope: List[TTP])(result: Exp[Any]): Boolean = true

  // -------------------
  

  override def emitBlockFocused(result: Exp[Any])(implicit stream: PrintWriter): Unit = {
    var currentScope = innerScope.map(fatten)
    currentScope = getFatSchedule(currentScope)(result) // clean things up!
    emitFatBlockFocused(currentScope)(result)
  }

  def emitFatBlockFocused(currentScope: List[TTP])(result: Exp[Any])(implicit stream: PrintWriter): Unit = {
    // do what super does, modulo fat stuff
    focusExactScopeFat(currentScope)(result) { levelScope => 
      for (TTP(syms, rhs) <- levelScope) {
        emitFatNode(syms, rhs)
      }
    }
  }

  def focusExactScopeFat[A](currentScope: List[TTP])(result: Exp[Any])(body: List[TTP] => A): A = {
    
    val saveInner = innerScope
    
    val e1 = currentScope
    shallow = true
    val e2 = getFatSchedule(currentScope)(result) // shallow list of deps (exclude stuff only needed by nested blocks)
    shallow = false

    // TODO: make sure currentScope schedule respects antidependencies

    // shallow is 'must outside + should outside' <--- currently shallow == deep for lambdas, meaning everything 'should outside'
    // bound is 'must inside'

    // find transitive dependencies on bound syms, including their defs (in case of effects)
    val bound = e1.flatMap(z => boundSyms(z.rhs))
    val g1 = getFatDependentStuff(currentScope)(bound)
    
    val levelScope = e1.filter(z => (e2 contains z) && !(g1 contains z)) // shallow (but with the ordering of deep!!) and minus bound

    // sanity check to make sure all effects are accounted for
    result match {
      case Def(Reify(x, u, effects)) =>
        val actual = levelScope.filter(_.lhs exists (effects contains _))
        if (effects != actual.flatMap(_.lhs)) {
          val expected = effects.map(d=>fatten(findDefinition(d.asInstanceOf[Sym[Any]]).get))
          println("error: violated ordering of effects")
          println("  expected:")
          expected.foreach(d => println("    "+d))
          println("  actual:")
          actual.foreach(d => println("    "+d))
          // stuff going missing because of stray dependencies is the most likely cause 
          // so let's print some debugging hints
          println("  missing:")
          val missing = expected filterNot (actual contains _)
          if (missing.isEmpty)
            println("  note: there is nothing missing so the different order might in fact be ok (artifact of new effect handling? TODO)")          
          missing.foreach { d => 
            val inDeep = e1 contains d
            val inShallow = e2 contains d
            val inDep = g1 contains d
            println("    "+d+" <-- inDeep: "+inDeep+", inShallow: "+inShallow+", inDep: "+inDep)
            if (inDep) e1 foreach { z =>
              val b = boundSyms(z.rhs)
              if (b.isEmpty) "" else {
                val g2 = getFatDependentStuff(currentScope)(b)
                if (g2 contains d) {
                  println("    depends on " + z + " (bound: "+b+")")
                  val path = getFatSchedule(g2)(d)
                  for (p <- path) println("      "+p)
                }
              }
            }
          }
        }
      case _ =>
    }
/*
    // sanity check to make sure all effects are accounted for
    result match {
      case Def(Reify(x, u, effects)) =>
        val actual = levelScope.filter(effects contains _.sym)
        assert(effects == actual.map(_.sym), "violated ordering of effects: expected \n    "+effects+"\nbut got\n    " + actual)
      case _ =>
    }
*/
    val innerScope2 = e1 diff levelScope // delay everything that remains


    innerScope = innerScope2 flatMap { 
      case TTP(List(sym), ThinDef(rhs)) => List(TP(sym, rhs))
      case e => 
        val z = innerScope.filter(e.lhs contains _.sym)
        if (z.length != e.lhs.length)
          println("TROUBLE: couldn't get syms " + e.lhs + ", found only " + z)
        z
    }

    val rval = body(levelScope)
    
    innerScope = saveInner
    rval
  }


  def emitFatNode(sym: List[Sym[Any]], rhs: FatDef)(implicit stream: PrintWriter): Unit = rhs match {
    case ThinDef(Reflect(s, u, effects)) => emitFatNode(sym, ThinDef(s)) // call back into emitFatNode, not emitNode
    case ThinDef(a) => emitNode(sym(0), a)
    case _ => sys.error("don't know how to generate code for: "+rhs)
  }


  case class Combine(a: List[Exp[Any]]) extends Exp[Any]

  def emitFatBlock(rhs: List[Exp[Any]])(implicit stream: PrintWriter): Unit = {
    emitBlock(Combine(rhs))
  }

  def focusFatBlock[A](rhs: List[Exp[Any]])(body: => A): A = {
    focusBlock(Combine(rhs))(body)
  }


}