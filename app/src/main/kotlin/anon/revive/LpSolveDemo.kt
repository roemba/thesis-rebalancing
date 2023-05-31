package anon.revive

import lpsolve.*

class LpSolveDemo {

    fun demo() {
      try {
        // Create a problem with 4 variables and 0 constraints
        val solver = LpSolve.makeLp(0, 4)
  
        // add constraints
        solver.strAddConstraint("3 2 2 1", LpSolve.LE, 4.0)
        solver.strAddConstraint("0 4 3 1", LpSolve.GE, 3.0)
  
        // set objective function
        solver.strSetObjFn("2 3 -2 3")
  
        // solve the problem
        solver.solve()
  
        // print solution
        println("Value of objective function: " + solver.getObjective())
        val pointerVariables = solver.getPtrVariables()
        for (i in 0 until pointerVariables.size) {
          println("Value of var[" + i + "] = " + pointerVariables[i])
        }
  
        // delete the problem and free memory
        solver.deleteLp()
      }
      catch (e: LpSolveException) {
         e.printStackTrace()
      }
    }
  
  }