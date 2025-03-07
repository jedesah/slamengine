/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.fs

import slamdata.Predef._
import quasar.{BackendCapability, Data}
import quasar.contrib.pathy._
import quasar.contrib.scalaz.foldable._
import quasar.fs.FileSystemTest.allFsUT

import pathy.Path._
import pathy.scalacheck.PathyArbitrary._
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import scalaz.stream._

class ManageFilesSpec extends FileSystemTest[BackendEffect](allFsUT.map(_ filter (_.ref supports BackendCapability.write()))) {
  import FileSystemTest._, FileSystemError._, PathError._

  val query  = QueryFile.Ops[BackendEffect]
  val read   = ReadFile.Ops[BackendEffect]
  val write  = WriteFile.Ops[BackendEffect]
  val manage = ManageFile.Ops[BackendEffect]

  val managePrefix: ADir = rootDir </> dir("m")

  fileSystemShould { (fs, _) =>
    implicit val run = fs.testInterpM

    "Managing Files" should {
      step(doDelete(fs.setupInterpM, managePrefix).runVoid)

      "moving a file should make it available at the new path and not found at the old" >> {
        val f1 = managePrefix </> dir("d1") </> file("f1")
        val f2 = managePrefix </> dir("d2") </> file("f2")
        val p = write.save(f1, oneDoc.toProcess).drain ++
                manage.moveFile(f1, f2, MoveSemantics.FailIfExists)
                  .liftM[Process].drain ++
                read.scanAll(f2).map(_.left[Boolean]) ++
                (query.fileExistsM(f1))
                  .liftM[Process]
                  .map(_.right[Data])

        runLogT(run, p).map(_.toVector.separate)
          .runEither must beRight((oneDoc, Vector(false)).zip(completelySubsume(_), equal(_)))
      }

      "moving a file to an existing path using FailIfExists semantics should fail with PathExists" >> {
        val f1 = managePrefix </> dir("failifexists") </> file("f1")
        val f2 = managePrefix </> dir("failifexists") </> file("f2")
        val expectedFiles = List[Node](Node.Data(FileName("f1")), Node.Data(FileName("f2")))
        val ls = query.ls(managePrefix </> dir("failifexists"))
        val p = write.save(f1, oneDoc.toProcess).drain ++
                write.save(f2, oneDoc.toProcess).drain ++
                manage.moveFile(f1, f2, MoveSemantics.FailIfExists).liftM[Process]

        (execT(run, p).runOption must beSome(pathErr(pathExists(f2)))) and
        (runT(run)(ls).runEither must beRight(containTheSameElementsAs(expectedFiles)))
      }

      "moving a file to an existing path with Overwrite semantics should make contents available at new path" >> {
        val f1 = managePrefix </> dir("overwrite") </> file("f1")
        val f2 = managePrefix </> dir("overwrite") </> file("f2")
        val p = write.save(f1, oneDoc.toProcess).drain ++
                write.save(f2, anotherDoc.toProcess).drain ++
                manage.moveFile(f1, f2, MoveSemantics.Overwrite)
                  .liftM[Process].drain ++
                read.scanAll(f2).map(_.left[Boolean]) ++
                (query.fileExistsM(f1))
                  .liftM[Process]
                  .map(_.right[Data])

        runLogT(run, p).map(_.toVector.separate)
          .runEither must beRight((oneDoc, Vector(false)).zip(completelySubsume(_), equal(_)))
      }

      "moving a file that doesn't exist to a file that does should fail with src NotFound" >> {
        val d = managePrefix </> dir("dnetoexists")
        val f1 = d </> file("f1")
        val f2 = d </> file("f2")
        val expectedFiles = List[Node](Node.Data(FileName("f2")))
        val ls = query.ls(d)
        val p = write.save(f2, oneDoc.toProcess).drain ++
                manage.moveFile(f1, f2, MoveSemantics.Overwrite)
                  .liftM[Process]

        (execT(run, p).runOption must beSome(pathErr(pathNotFound(f1)))) and
        (runT(run)(ls).runEither must beRight(containTheSameElementsAs(expectedFiles)))
      }

      "moving a file that doesn't exist to a file that also doesn't exist should fail with src NotFound" >> {
        val d = managePrefix </> dir("dnetodne")
        val f1 = d </> file("f1")
        val f2 = d </> file("f2")

        runT(run)(manage.moveFile(f1, f2, MoveSemantics.Overwrite))
          .runOption must beSome(pathErr(pathNotFound(f1)))
      }

      "moving a file to itself with FailIfExists semantics should fail with PathExists" >> {
        val f1 = managePrefix </> dir("selftoself") </> file("f1")
        val p  = write.save(f1, oneDoc.toProcess).drain ++
                 manage.moveFile(f1, f1, MoveSemantics.FailIfExists).liftM[Process]

        execT(run, p).runOption must beSome(pathErr(pathExists(f1)))
      }

      "moving a file to a nonexistent path when using FailIfMissing semantics should fail with dst NotFound" >> {
        val d = managePrefix </> dir("existstodne")
        val f1 = d </> file("f1")
        val f2 = d </> file("f2")
        val expectedFiles = List[Node](Node.Data(FileName("f1")))
        val p = write.saveThese(f1, oneDoc) >>
                manage.moveFile(f1, f2, MoveSemantics.FailIfMissing)

        (run(p.run).unsafePerformSync must_= pathErr(pathNotFound(f2)).left) and
        (run(query.ls(d).run).unsafePerformSync.toEither must beRight(containTheSameElementsAs(expectedFiles)))
      }

      "moving a directory should move all files therein to dst path" >> {
        val d = managePrefix </> dir("movedir")
        val d1 = d </> dir("d1")
        val d2 = d </> dir("d2")
        val f1 = d1 </> file("f1")
        val f2 = d1 </> file("f2")

        val expectedFiles = List[Node](Node.Data(FileName("f1")), Node.Data(FileName("f2")))

        val p = write.save(f1, oneDoc.toProcess).drain ++
                write.save(f2, anotherDoc.toProcess).drain ++
                manage.moveDir(d1, d2, MoveSemantics.FailIfExists).liftM[Process]

        (execT(run, p).runOption must beNone) and
        (runT(run)(query.ls(d2)).runEither must beRight(containTheSameElementsAs(expectedFiles))) and
        (runT(run)(query.ls(d1)).runEither must beLeft(pathErr(pathNotFound(d1))))
      }

      // fixes #2973
      "moving a directory to not existing path should create it" >> {
        val root = managePrefix </> dir("d1")
        val d2 = root </> dir("d2")
        val trash = root </> dir(".trash")
        val trash_d2 = trash </> dir("d2")

        val f = d2 </> file("f")

        val expectedRoot = List[Node](Node.ImplicitDir(DirName(".trash")))
        val expectedTrash = List[Node](Node.ImplicitDir(DirName("d2")))
        val expectedFiles = List[Node](Node.Data(FileName("f")))

        val p = write.save(f, oneDoc.toProcess).drain ++
                manage.moveDir(d2, trash_d2, MoveSemantics.FailIfExists).liftM[Process]

        (execT(run, p).runOption must beNone) and
        (runT(run)(query.ls(root)).runEither must beRight(containTheSameElementsAs(expectedRoot))) and
        (runT(run)(query.ls(trash)).runEither must beRight(containTheSameElementsAs(expectedTrash))) and
        (runT(run)(query.ls(trash_d2)).runEither must beRight(containTheSameElementsAs(expectedFiles))) and
        (runT(run)(query.ls(d2)).runEither must beLeft(pathErr(pathNotFound(d2))))
      }

      "files and directories with spaces/dots in names should be supported" >> {
        val root = managePrefix </> dir("rt")
        val d1 = root </> dir("Some Directory")
        val folder1 = d1 </> dir(".folder1")
        val folder2 = d1 </> dir(".folder2")
        val fDot = folder1 </> file(".patients")
        val fSpace = folder2 </> file("all patients")

        val expectedRoot = List[Node](Node.ImplicitDir(DirName("Some Directory")))
        val expectedD1 = List[Node](Node.ImplicitDir(DirName(".folder1")), Node.ImplicitDir(DirName(".folder2")))
        val expectedFDot = List[Node](Node.Data(FileName(".patients")))
        val expectedFSpace = List[Node](Node.Data(FileName("all patients")))

        val p =
          write.save(fDot, oneDoc.toProcess).drain ++
                write.save(fSpace, oneDoc.toProcess).drain

        (execT(run, p).runOption must beNone) and
          (runT(run)(query.ls(root)).runEither must beRight(containTheSameElementsAs(expectedRoot))) and
          (runT(run)(query.ls(d1)).runEither must beRight(containTheSameElementsAs(expectedD1))) and
          (runT(run)(query.ls(folder1)).runEither must beRight(containTheSameElementsAs(expectedFDot))) and
          (runT(run)(query.ls(folder2)).runEither must beRight(containTheSameElementsAs(expectedFSpace)))
      }

      "moving a nonexistent dir to another nonexistent dir fails with src NotFound" >> {
        val d1 = managePrefix </> dir("dirdnetodirdne") </> dir("d1")
        val d2 = managePrefix </> dir("dirdnetodirdne") </> dir("d2")

        runT(run)(manage.moveDir(d1, d2, MoveSemantics.FailIfExists))
          .runOption must beSome(pathErr(pathNotFound(d1)))
      }

      "[SD-1846] moving a directory with a name that is a prefix of another directory" >> {
        // TODO: folder filenames have been shortened to workaround PostgreSQL table name length restriction — revisit
        val pnt = managePrefix </> dir("SD1846")
        val uf1 = pnt </> dir("UF")   </> file("one")
        val uf2 = pnt </> dir("UF1") </> file("two")
        val uf3 = pnt </> dir("UF2") </> file("three")

        val thirdDoc: Vector[Data] =
          Vector(Data.Obj(ListMap("c" -> Data.Int(1))))

        val src = pnt </> dir("UF")
        val dst = pnt </> dir("UF1") </> dir("UF")

        val setupAndMove =
          write.saveThese(uf1, oneDoc)     *>
          write.saveThese(uf2, anotherDoc) *>
          write.saveThese(uf3, thirdDoc)   *>
          manage.moveDir(src, dst, MoveSemantics.FailIfExists)

        (runT(run)(setupAndMove).runOption must beNone)                                                                                  and
        (runLogT(run, read.scanAll(dst </> file("one"))).runEither must beRight(completelySubsume(oneDoc))) and
        (run(query.fileExists(src </> file("one"))).unsafePerformSync must beFalse)
      }

      "copying" >> {
        val f1 = managePrefix </> dir("d1") </> file("f1")
        val f2 = managePrefix </> dir("d2") </> file("f2")
        val p =
          write.save(f1, oneDoc.toProcess).drain ++
          manage.copyFile(f1, f2).liftM[Process].drain ++
          read.scanAll(f2) ++
          read.scanAll(f1)

        ifSupported(runLogT(run, p)) { r =>
          Task.delay(r.toEither must beRight(oneDoc ++ oneDoc))
        }
      }

      "deleting a nonexistent file returns PathNotFound" >> {
        val f = managePrefix </> file("delfilenotfound")
        runT(run)(manage.delete(f)).runEither must beLeft(pathErr(pathNotFound(f)))
      }

      "deleting a file makes it no longer accessible" >> {
        val f1 = managePrefix </> dir("deleteone") </> file("f1")
        val p  = write.save(f1, oneDoc.toProcess).drain ++ manage.delete(f1).liftM[Process]

        (execT(run, p).runOption must beNone) and
        (runLogT(run, read.scanAll(f1)).runEither must beRight(Vector.empty[Data]))
      }

      "deleting a file with siblings in directory leaves siblings untouched" >> {
        val d = managePrefix </> dir("withsiblings")
        val f1 = d </> file("f1")
        val f2 = d </> file("f2")

        val p = write.save(f1, oneDoc.toProcess).drain ++
                write.save(f2, anotherDoc.toProcess).drain ++
                manage.delete(f1).liftM[Process]

        (execT(run, p).runOption must beNone)                                       and
        (runLogT(run, read.scanAll(f1)).runEither must beRight(Vector.empty[Data])) and
        (runLogT(run, read.scanAll(f2)).runEither must beRight(completelySubsume(anotherDoc)))
      }

      "deleting a directory deletes all files therein" >> {
        val d = managePrefix </> dir("deldir")
        val f1 = d </> file("f1")
        val f2 = d </> file("f2")

        val p = write.save(f1, oneDoc.toProcess).drain ++
                write.save(f2, anotherDoc.toProcess).drain ++
                manage.delete(d).liftM[Process]

        ifSupported(execT(run, p)) { res =>
          for {
            r1 <- Task.delay(res.swap.toOption must beNone)
            f1data <- runLogT(run, read.scanAll(f1)).run
            f2data <- runLogT(run, read.scanAll(f2)).run
            lsd <- runT(run)(query.ls(d)).run
          } yield {
            r1 and
            (f1data.toEither must beRight(Vector.empty[Data])) and
            (f2data.toEither must beRight(Vector.empty[Data])) and
            (lsd.toEither must beLeft(pathErr(pathNotFound(d))))
          }
        }
      }

      "deleting a nonexistent directory returns PathNotFound" >> {
        val d = managePrefix </> dir("deldirnotfound")

        ifSupported(runT(run)(manage.delete(d))) { r =>
          Task.delay(r.toEither must beLeft(pathErr(pathNotFound(d))))
        }
      }

      "write/read from temp dir near existing" >> {
        val d = managePrefix </> dir("tmpnear1")
        val f = d </> file("somefile")

        val p = write.save(f, oneDoc.toProcess).drain ++
                manage.tempFile(f, TempFilePrefix("some_prefix_").some).liftM[Process] flatMap { tf =>
                  write.save(tf, anotherDoc.toProcess).drain ++
                  read.scanAll(tf) ++
                  manage.delete(tf).liftM[Process].drain
                }

        runLogT(run, p).runEither must beRight(completelySubsume(anotherDoc))
      }

      "write/read from temp dir near non existing" >> {
        val d = managePrefix </> dir("tmpnear2")
        val f = d </> file("somefile")
        val p = manage.tempFile(f, None).liftM[Process] flatMap { tf =>
                  write.save(tf, anotherDoc.toProcess).drain ++
                  read.scanAll(tf) ++
                  manage.delete(tf).liftM[Process].drain
                }

        runLogT(run, p).runEither must beRight(completelySubsume(anotherDoc))
      }

      "temp file should be generated in hint directory" >> prop { rdir: RDir =>
        val hintDir = managePrefix </> rdir

        runT(run)(manage.tempFile(hintDir, None))
          .map(_ relativeTo hintDir)
          .runEither must beRight(beSome[RFile])
      }

      "temp file should be generated in parent of hint file" >> prop { rfile: RFile =>
        val hintFile = managePrefix </> rfile
        val hintDir  = fileParent(hintFile)

        runT(run)(manage.tempFile(hintFile, None))
          .map(_ relativeTo hintDir)
          .runEither must beRight(beSome[RFile])
      }

      step(doDelete(fs.setupInterpM, managePrefix).runVoid)
    }
  }
}
