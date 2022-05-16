package effekt

import effekt.context.Context
import effekt.substitutions.TypeComparer
import effekt.symbols.*
import effekt.symbols.builtins.{ TBottom, TTop }
import effekt.util.messages.ErrorReporter

object substitutions {

  private var scopeId: Int = 0

  case class ValueTypeConstraints(lower: Set[ValueType], upper: Set[ValueType])
  case class CaptureConstraints(lower: Set[Capture], upper: Set[Capture])

  /**
   * The state of the unification scope, used for backtracking on overload resolution
   *
   * See [[UnificationScope.backup]] and [[UnificationScope.restore]]
   */
  case class UnificationState(
    skolems: List[UnificationVar],
    valueConstraints: Map[UnificationVar, ValueTypeConstraints]
  )

  /**
   * A unification scope -- every fresh unification variable is associated with a scope.
   */
  class UnificationScope { self =>

    val id = { scopeId += 1; scopeId }

    // the state of this unification scope

    private var skolems: List[UnificationVar] = Nil
    private var capture_skolems: List[CaptureUnificationVar] = Nil
    var valueConstraints: Map[UnificationVar, ValueTypeConstraints] = Map.empty


    def backup(): UnificationState = UnificationState(skolems, valueConstraints)
    def restore(state: UnificationState): Unit =
      skolems = state.skolems
      valueConstraints = state.valueConstraints

    def fresh(role: UnificationVar.Role): UnificationVar = {
      val x = UnificationVar(role, this)
      skolems = x :: skolems
      x
    }

    def freshCaptVar(underlying: Capture): CaptureUnificationVar = {
      val x = CaptureUnificationVar(underlying, this)
      capture_skolems = x :: capture_skolems
      x
    }

    def requireSubtype(t1: ValueType, t2: ValueType)(using C: ErrorReporter): Unit = {
      println(valueConstraints)
      println(s"requireSubtype ${t1} <: ${t2}")
      comparer.unifyValueTypes(t1, t2)
      //sys error s"Requiring that ${t1} <:< ${t2}"
    }

    def requireSubtype(t1: BlockType, t2: BlockType)(using C: ErrorReporter): Unit =
      sys error s"Requiring that ${t1} <:< ${t2}"

    def requireSubregion(c1: CaptureSet, c2: CaptureSet)(using C: ErrorReporter): Unit =
      sys error s"Requiring that ${c1} <:< ${c2}"

    /**
     * Given the current unification state, can we decide whether one effect is a subtype of another?
     *
     * Used to subtract one set of effects from another (when checking handling, or higher-order functions)
     */
    def isSubtype(e1: Effect, e2: Effect): Boolean = ???

    /**
     * Removes effects [[effs2]] from effects [[effs1]] by checking for subtypes.
     *
     * TODO check whether this is sound! It should be, since it is a conservative approximation.
     *   If it turns out two effects ARE subtypes after all, and we have not removed them, it does not
     *   compromise effect safety.
     *
     * TODO potentially dealias first...
     */
    def subtract(effs1: Effects, effs2: Effects): Effects =
      effs1.filterNot(eff1 => effs2.exists(eff2 => isSubtype(eff2, eff1)))

    /**
     * Instantiate a typescheme with fresh, rigid type variables
     *
     * i.e. `[A, B] (A, A) => B` becomes `(?A, ?A) => ?B`
     */
    def instantiate(tpe: FunctionType)(using C: ErrorReporter): (List[UnificationVar], List[CaptureUnificationVar], FunctionType) = {
      val FunctionType(tparams, cparams, vparams, bparams, ret, eff) = tpe
      val typeRigids = tparams map { t => fresh(UnificationVar.TypeVariableInstantiation(t)) }
      val captRigids = cparams map freshCaptVar
      val subst = Substitutions(
        tparams zip typeRigids,
        cparams zip captRigids.map(c => CaptureSet(c)))

      val substitutedVparams = vparams map subst.substitute
      val substitutedBparams = bparams map subst.substitute
      val substitutedReturn = subst.substitute(ret)
      val substitutedEffects = subst.substitute(eff)
      (typeRigids, captRigids, FunctionType(Nil, Nil, substitutedVparams, substitutedBparams, substitutedReturn, substitutedEffects))
    }

    def checkConsistency(using C: ErrorReporter): TypeComparer = new TypeComparer {
      def currentScope = self
      def defer(t1: ValueType, t2: ValueType): Unit = ???
      def unify(c1: CaptureSet, c2: CaptureSet): Unit = ???
      def abort(msg: String) = C.abort(msg)
      def learnLowerBound(x: UnificationVar, tpe: ValueType) = ()
      def learnUpperBound(x: UnificationVar, tpe: ValueType) = ()
    }

    // TODO the comparer should build up a "deconstruction trace" that can be used for better
    //   type errors.
    def comparer(using C: ErrorReporter): TypeComparer = new TypeComparer {
      def currentScope = self

      def unify(c1: CaptureSet, c2: CaptureSet): Unit = ??? // ccs = EqCapt(c1, c2, C.focus) :: ccs

      def abort(msg: String) = C.abort(msg)

      def learnLowerBound(x: UnificationVar, tpe: ValueType) = constrainLower(x, tpe)

      def learnUpperBound(x: UnificationVar, tpe: ValueType) = constrainUpper(x, tpe)

      def mergeUpperBounds(prev: ValueType, next: ValueType): ValueType = ???

      def constrainLower(x: UnificationVar, tpe: ValueType): Unit =
        if (x == tpe) return ()
        println(valueConstraints)
        val ValueTypeConstraints(lower, upper) = valueConstraints.getOrElse(x, ValueTypeConstraints(Set.empty, Set.empty))
        if (lower contains tpe) return () // necessary for preventing looping
        // TODO check for consistency within lower.
        //   the current comparison does not check bounds of unification variables
//        lower.foreach { other =>
//          // catching the exception here is only a hack, for now!
//          try checkConsistency.unifyValueTypes(other, tpe) catch {
//            case e =>
//              x.role match {
//                case UnificationVar.InferredReturn(tree) => C.at(tree) { C.abort("Inconsistency in return position") }
//                case UnificationVar.TypeVariableInstantiation(underlying) => C.abort("Inconsistency in type application")
//              }
//          }
//        }
        valueConstraints = valueConstraints.updated(x, ValueTypeConstraints(lower + tpe, upper))
        upper.foreach {
          // propagate into upper bounds...
          case u: UnificationVar => constrainLower(u, tpe)
          // check for consistency with concrete upper bounds...
          case t: ValueType => requireSubtype(tpe, t)
        }

      def constrainUpper(x: UnificationVar, tpe: ValueType): Unit =
        // TODO check whether x == tpe and don't do anything,
        if (x == tpe) return ()
        println(valueConstraints)
        val ValueTypeConstraints(lower, upper) = valueConstraints.getOrElse(x, ValueTypeConstraints(Set.empty, Set.empty))
        if (upper contains tpe) return ()
        // TODO check for consistency within upper.
        // upper.foreach { other => checkConsistency.unifyValueTypes(tpe, other) }
        valueConstraints = valueConstraints.updated(x, ValueTypeConstraints(lower, upper + tpe))
        upper.foreach {
          // propagate into upper bounds...
          case u: UnificationVar => constrainUpper(u, tpe)
          // check for consistency with concrete upper bounds...
          case t: ValueType => requireSubtype(t, tpe)
        }



//        println(s"We learnt that ${x} <: ${tpe}")
//
//       {
//        // all existing solutions have to be compatible with the new one
//        equivalences.solutions(x).foreach { s => push(Eq(tpe, s, C.focus)) }
//        equivalences.add(x, tpe)
//      }
    }
  }

  case class SubstitutionException(x: CaptureUnificationVar, subst: Map[Capture, CaptureSet]) extends Exception

  /**
   * Substitutions not only have unification variables as keys, since we also use the same mechanics to
   * instantiate type schemes
   */
  case class Substitutions(
    values: Map[TypeVar, ValueType],
    captures: Map[Capture, CaptureSet]
  ) {

    def isDefinedAt(t: TypeVar) = values.isDefinedAt(t)
    def isDefinedAt(c: Capture) = captures.isDefinedAt(c)

    def get(t: TypeVar) = values.get(t)
    def get(c: Capture) = captures.get(c)

    // amounts to first substituting this, then other
    def updateWith(other: Substitutions): Substitutions =
      Substitutions(values.view.mapValues { t => other.substitute(t) }.toMap, captures.view.mapValues { t => other.substitute(t) }.toMap) ++ other

    // amounts to parallel substitution
    def ++(other: Substitutions): Substitutions = Substitutions(values ++ other.values, captures ++ other.captures)

    // shadowing
    private def without(tps: List[TypeVar], cps: List[Capture]): Substitutions =
      Substitutions(
        values.filterNot { case (t, _) => tps.contains(t) },
        captures.filterNot { case (t, _) => cps.contains(t) }
      )

    // TODO we DO need to distinguish between substituting unification variables for unification variables
    // and substituting concrete captures in unification variables... These are two fundamentally different operations.
    def substitute(c: CaptureSet): CaptureSet = c.flatMap {
      // we are probably instantiating a function type
      case x: CaptureUnificationVar if captures.keys.exists(c => c.concrete) =>
        throw SubstitutionException(x, captures)
      case c => captures.getOrElse(c, CaptureSet(c))
    }

    def substitute(t: ValueType): ValueType = t match {
      case x: TypeVar =>
        values.getOrElse(x, x)
      case ValueTypeApp(t, args) =>
        ValueTypeApp(t, args.map { substitute })
      case BoxedType(tpe, capt) =>
        BoxedType(substitute(tpe), substitute(capt))
      case other => other
    }

    // TODO implement
    def substitute(t: Effects): Effects = t
    def substitute(t: Effect): Effect = t

    def substitute(t: BlockType): BlockType = t match {
      case e: InterfaceType => substitute(e)
      case b: FunctionType        => substitute(b)
    }

    def substitute(t: InterfaceType): InterfaceType = t match {
      case b: Interface           => b
      case BlockTypeApp(c, targs) => BlockTypeApp(c, targs map substitute)
    }

    def substitute(t: FunctionType): FunctionType = t match {
      case FunctionType(tps, cps, vps, bps, ret, eff) =>
        // do not substitute with types parameters bound by this function!
        val substWithout = without(tps, cps)
        FunctionType(
          tps,
          cps,
          vps map substWithout.substitute,
          bps map substWithout.substitute,
          substWithout.substitute(ret),
          substWithout.substitute(eff))
    }
  }

  object Substitutions {
    val empty: Substitutions = Substitutions(Map.empty[TypeVar, ValueType], Map.empty[Capture, CaptureSet])
    def apply(values: List[(TypeVar, ValueType)], captures: List[(Capture, CaptureSet)]): Substitutions = Substitutions(values.toMap, captures.toMap)
  }

  // TODO Mostly for backwards compat
  implicit def typeMapToSubstitution(values: Map[TypeVar, ValueType]): Substitutions = Substitutions(values, Map.empty[Capture, CaptureSet])
  implicit def captMapToSubstitution(captures: Map[Capture, CaptureSet]): Substitutions = Substitutions(Map.empty[TypeVar, ValueType], captures)


  trait TypeComparer {

    // "unification effects"
    def learnLowerBound(x: UnificationVar, tpe: ValueType): Unit
    def learnUpperBound(x: UnificationVar, tpe: ValueType): Unit
    def abort(msg: String): Nothing
    def currentScope: UnificationScope

    def unify(c1: CaptureSet, c2: CaptureSet): Unit

    def unify(c1: Capture, c2: Capture): Unit = unify(CaptureSet(Set(c1)), CaptureSet(Set(c2)))

    def unifyValueTypes(tpe1: ValueType, tpe2: ValueType): Unit = (tpe1, tpe2) match {
      case (t, s) if t == s => ()
      case (_, TTop) => ()
      case (TBottom, _) => ()

      case (s: UnificationVar, t: ValueType) => learnUpperBound(s, t)

      // occurs for example when checking the first argument of `(1 + 2) == 3` against expected
      // type `?R` (since `==: [R] (R, R) => Boolean`)
      case (s: ValueType, t: UnificationVar)=> learnLowerBound(t, s)

      case (ValueTypeApp(t1, args1), ValueTypeApp(t2, args2)) =>
        if (args1.size != args2.size)
          abort(s"Argument count does not match $t1 vs. $t2")

        unifyValueTypes(t1, t2)

        (args1 zip args2) foreach { case (t1, t2) => unifyValueTypes(t1, t2) }

      case (BoxedType(tpe1, capt1), BoxedType(tpe2, capt2)) =>
        unifyBlockTypes(tpe1, tpe2)
        unify(capt1, capt2)

      case (t, s) =>
        abort(s"Expected ${t}, but got ${s}")
    }

    def unifyBlockTypes(tpe1: BlockType, tpe2: BlockType): Unit = (tpe1, tpe2) match {
      case (t: FunctionType, s: FunctionType) => unifyFunctionTypes(t, s)
      case (t: InterfaceType, s: InterfaceType) => unifyInterfaceTypes(t, s)
      case (t, s) => abort(s"Expected ${t}, but got ${s}")
    }

    def unifyInterfaceTypes(tpe1: InterfaceType, tpe2: InterfaceType): Unit = (tpe1, tpe2) match {
      case (t1: Interface, t2: Interface) => if (t1 != t2) abort(s"Expected ${t1}, but got ${t2}")
      case (BlockTypeApp(c1, targs1), BlockTypeApp(c2, targs2)) =>
        unifyInterfaceTypes(c2, c2)
        (targs1 zip targs2) foreach { case (t1, t2) => unifyValueTypes(t1, t2) }
      case _ => abort(s"Kind mismatch between ${tpe1} and ${tpe2}")
    }

    def unifyEffects(eff1: Effects, eff2: Effects): Unit = ???

    def unifyFunctionTypes(tpe1: FunctionType, tpe2: FunctionType): Unit = (tpe1, tpe2) match {
      case (f1 @ FunctionType(tparams1, cparams1, vparams1, bparams1, ret1, eff1), f2 @ FunctionType(tparams2, cparams2, vparams2, bparams2, ret2, eff2)) =>

        if (tparams1.size != tparams2.size)
          abort(s"Type parameter count does not match $f1 vs. $f2")

        if (vparams1.size != vparams2.size)
          abort(s"Value parameter count does not match $f1 vs. $f2")

        if (bparams1.size != bparams2.size)
          abort(s"Block parameter count does not match $f1 vs. $f2")

        if (cparams1.size != cparams2.size)
          abort(s"Capture parameter count does not match $f1 vs. $f2")

        val subst = Substitutions(tparams2 zip tparams1, cparams2 zip cparams1.map(c => CaptureSet(c)))

        val (substVparams2, substBparams2, substRet2) = (vparams2 map subst.substitute, bparams2 map subst.substitute, subst.substitute(ret2))

        (vparams1 zip substVparams2) foreach { case (t1, t2) => unifyValueTypes(t2, t1) }
        (bparams1 zip substBparams2) foreach { case (t1, t2) => unifyBlockTypes(t2, t1) }
        unifyValueTypes(ret1, substRet2)
        unifyEffects(eff1, eff2)
    }

    // There are only a few users of dealiasing:
    //  1) checking for effect inclusion (`contains` in Effects)
    //  2) checking exhaustivity of pattern matching
    //  3) type comparer itself
    def dealias(tpe: ValueType): ValueType = ???
    def dealias(tpe: Effects): Effects = ???
  }
}
