/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.notebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import org.apache.zeppelin.display.AngularObjectRegistry;
import org.apache.zeppelin.interpreter.InterpreterFactory;
import org.apache.zeppelin.interpreter.InterpreterOption;
import org.apache.zeppelin.interpreter.mock.MockInterpreter1;
import org.apache.zeppelin.interpreter.mock.MockInterpreter2;
import org.apache.zeppelin.notebook.repo.NotebookRepo;
import org.apache.zeppelin.notebook.repo.VFSNotebookRepo;
import org.apache.zeppelin.scheduler.Job;
import org.apache.zeppelin.scheduler.Job.Status;
import org.apache.zeppelin.scheduler.JobListener;
import org.apache.zeppelin.scheduler.SchedulerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.quartz.SchedulerException;

public class NotebookTest implements JobListenerFactory{

  private File tmpDir;
  private ZeppelinConfiguration conf;
  private SchedulerFactory schedulerFactory;
  private File notebookDir;
  private Notebook notebook;
  private NotebookRepo notebookRepo;
  private InterpreterFactory factory;

  @Before
  public void setUp() throws Exception {
    tmpDir = new File(System.getProperty("java.io.tmpdir")+"/ZeppelinLTest_"+System.currentTimeMillis());
    tmpDir.mkdirs();
    new File(tmpDir, "conf").mkdirs();
    notebookDir = new File(System.getProperty("java.io.tmpdir")+"/ZeppelinLTest_"+System.currentTimeMillis()+"/notebook");
    notebookDir.mkdirs();

    System.setProperty(ConfVars.ZEPPELIN_HOME.getVarName(), tmpDir.getAbsolutePath());
    System.setProperty(ConfVars.ZEPPELIN_NOTEBOOK_DIR.getVarName(), notebookDir.getAbsolutePath());
    System.setProperty(ConfVars.ZEPPELIN_INTERPRETERS.getVarName(), "org.apache.zeppelin.interpreter.mock.MockInterpreter1,org.apache.zeppelin.interpreter.mock.MockInterpreter2");

    conf = ZeppelinConfiguration.create();

    this.schedulerFactory = new SchedulerFactory();

    MockInterpreter1.register("mock1", "org.apache.zeppelin.interpreter.mock.MockInterpreter1");
    MockInterpreter2.register("mock2", "org.apache.zeppelin.interpreter.mock.MockInterpreter2");

    factory = new InterpreterFactory(conf, new InterpreterOption(false), null);

    notebookRepo = new VFSNotebookRepo(conf);
    notebook = new Notebook(conf, notebookRepo, schedulerFactory, factory, this);
  }

  @After
  public void tearDown() throws Exception {
    delete(tmpDir);
  }

  @Test
  public void testSelectingReplImplementation() throws IOException {
    Note note = notebook.createNote();
    note.getNoteReplLoader().setInterpreters(factory.getDefaultInterpreterSettingList());

    // run with defatul repl
    Paragraph p1 = note.addParagraph();
    Map config = p1.getConfig();
    config.put("enabled", true);
    p1.setConfig(config);
    p1.setText("hello world");
    note.run(p1.getId());
    while(p1.isTerminated()==false || p1.getResult()==null) Thread.yield();
    assertEquals("repl1: hello world", p1.getResult().message());

    // run with specific repl
    Paragraph p2 = note.addParagraph();
    p2.setConfig(config);
    p2.setText("%mock2 hello world");
    note.run(p2.getId());
    while(p2.isTerminated()==false || p2.getResult()==null) Thread.yield();
    assertEquals("repl2: hello world", p2.getResult().message());
  }

  @Test
  public void testGetAllNotes() throws IOException {
    // get all notes after copy the {notebookId}/note.json into notebookDir
    File srcDir = new File("src/test/resources/2A94M5J1Z");
    File destDir = new File(notebookDir.getAbsolutePath() + "/2A94M5J1Z");

    try {
      FileUtils.copyDirectory(srcDir, destDir);
    } catch (IOException e) {
      e.printStackTrace();
    }

    Note copiedNote = notebookRepo.get("2A94M5J1Z");

    // when ZEPPELIN_NOTEBOOK_GET_FROM_REPO set to be false
    System.setProperty(ConfVars.ZEPPELIN_NOTEBOOK_RELOAD_FROM_STORAGE.getVarName(), "false");
    List<Note> notes = notebook.getAllNotes();
    assertEquals(notes.size(), 0);

    // when ZEPPELIN_NOTEBOOK_GET_FROM_REPO set to be true
    System.setProperty(ConfVars.ZEPPELIN_NOTEBOOK_RELOAD_FROM_STORAGE.getVarName(), "true");
    notes = notebook.getAllNotes();
    assertEquals(notes.size(), 1);
    assertEquals(notes.get(0).id(), copiedNote.id());
    assertEquals(notes.get(0).getName(), copiedNote.getName());
    assertEquals(notes.get(0).getParagraphs(), copiedNote.getParagraphs());

    // get all notes after remove the {notebookId}/note.json from notebookDir
    // when ZEPPELIN_NOTEBOOK_GET_FROM_REPO set to be false
    System.setProperty(ConfVars.ZEPPELIN_NOTEBOOK_RELOAD_FROM_STORAGE.getVarName(), "false");
    // delete the notebook
    FileUtils.deleteDirectory(destDir);
    notes = notebook.getAllNotes();
    assertEquals(notes.size(), 1);

    // when ZEPPELIN_NOTEBOOK_GET_FROM_REPO set to be true
    System.setProperty(ConfVars.ZEPPELIN_NOTEBOOK_RELOAD_FROM_STORAGE.getVarName(), "true");
    notes = notebook.getAllNotes();
    assertEquals(notes.size(), 0);
  }

  @Test
  public void testPersist() throws IOException, SchedulerException{
    Note note = notebook.createNote();

    // run with default repl
    Paragraph p1 = note.addParagraph();
    Map config = p1.getConfig();
    config.put("enabled", true);
    p1.setConfig(config);
    p1.setText("hello world");
    note.persist();

    Notebook notebook2 = new Notebook(conf, notebookRepo, schedulerFactory, new InterpreterFactory(conf, null), this);
    assertEquals(1, notebook2.getAllNotes().size());
  }

  @Test
  public void testClearParagraphOutput() throws IOException, SchedulerException{
    Note note = notebook.createNote();
    Paragraph p1 = note.addParagraph();
    Map config = p1.getConfig();
    config.put("enabled", true);
    p1.setConfig(config);
    p1.setText("hello world");
    note.run(p1.getId());

    while(p1.isTerminated()==false || p1.getResult()==null) Thread.yield();
    assertEquals("repl1: hello world", p1.getResult().message());

    // clear paragraph output/result
    note.clearParagraphOutput(p1.getId());
    assertNull(p1.getResult());
  }

  @Test
  public void testRunAll() throws IOException {
    Note note = notebook.createNote();
    note.getNoteReplLoader().setInterpreters(factory.getDefaultInterpreterSettingList());
    Paragraph p1 = note.addParagraph();
    Map config = p1.getConfig();
    config.put("enabled", true);
    p1.setConfig(config);
    p1.setText("p1");
    Paragraph p2 = note.addParagraph();
    Map config1 = p2.getConfig();
    p2.setConfig(config1);
    p2.setText("p2");
    assertEquals(null, p2.getResult());
    note.runAll();

    while(p2.isTerminated()==false || p2.getResult()==null) Thread.yield();
    assertEquals("repl1: p2", p2.getResult().message());
  }

  @Test
  public void testSchedule() throws InterruptedException, IOException{
    // create a note and a paragraph
    Note note = notebook.createNote();
    note.getNoteReplLoader().setInterpreters(factory.getDefaultInterpreterSettingList());

    Paragraph p = note.addParagraph();
    Map config = new HashMap<String, Object>();
    p.setConfig(config);
    p.setText("p1");
    Date dateFinished = p.getDateFinished();
    assertNull(dateFinished);

    // set cron scheduler, once a second
    config = note.getConfig();
    config.put("enabled", true);
    config.put("cron", "* * * * * ?");
    note.setConfig(config);
    notebook.refreshCron(note.id());
    Thread.sleep(1*1000);
    
    // remove cron scheduler.
    config.put("cron", null);
    note.setConfig(config);
    notebook.refreshCron(note.id());
    Thread.sleep(1000);
    dateFinished = p.getDateFinished();
    assertNotNull(dateFinished);
    Thread.sleep(1*1000);
    assertEquals(dateFinished, p.getDateFinished());
  }

  @Test
  public void testCloneNote() throws IOException, CloneNotSupportedException,
      InterruptedException {
    Note note = notebook.createNote();
    note.getNoteReplLoader().setInterpreters(factory.getDefaultInterpreterSettingList());

    final Paragraph p = note.addParagraph();
    p.setText("hello world");
    note.runAll();
    while(p.isTerminated()==false || p.getResult()==null) Thread.yield();

    p.setStatus(Status.RUNNING);
    Note cloneNote = notebook.cloneNote(note.getId(), "clone note");
    Paragraph cp = cloneNote.paragraphs.get(0);
    assertEquals(cp.getStatus(), Status.READY);
    assertNotEquals(cp.getId(), p.getId());
    assertEquals(cp.text, p.text);
    assertEquals(cp.getResult().message(), p.getResult().message());
  }

  @Test
  public void testAngularObjectRemovalOnNotebookRemove() throws InterruptedException,
      IOException {
    // create a note and a paragraph
    Note note = notebook.createNote();
    note.getNoteReplLoader().setInterpreters(factory.getDefaultInterpreterSettingList());

    AngularObjectRegistry registry = note.getNoteReplLoader()
        .getInterpreterSettings().get(0).getInterpreterGroup()
        .getAngularObjectRegistry();

    // add local scope object
    registry.add("o1", "object1", note.id());
    // add global scope object
    registry.add("o2", "object2", null);

    // remove notebook
    notebook.removeNote(note.id());

    // local object should be removed
    assertNull(registry.get("o1", note.id()));
    // global object sould be remained
    assertNotNull(registry.get("o2", null));
  }

  @Test
  public void testAngularObjectRemovalOnInterpreterRestart() throws InterruptedException,
      IOException {
    // create a note and a paragraph
    Note note = notebook.createNote();
    note.getNoteReplLoader().setInterpreters(factory.getDefaultInterpreterSettingList());

    AngularObjectRegistry registry = note.getNoteReplLoader()
        .getInterpreterSettings().get(0).getInterpreterGroup()
        .getAngularObjectRegistry();

    // add local scope object
    registry.add("o1", "object1", note.id());
    // add global scope object
    registry.add("o2", "object2", null);

    // restart interpreter
    factory.restart(note.getNoteReplLoader().getInterpreterSettings().get(0).id());
    registry = note.getNoteReplLoader()
    .getInterpreterSettings().get(0).getInterpreterGroup()
    .getAngularObjectRegistry();

    // local and global scope object should be removed
    assertNull(registry.get("o1", note.id()));
    assertNull(registry.get("o2", null));
    notebook.removeNote(note.id());
  }

  @Test
  public void testAbortParagraphStatusOnInterpreterRestart() throws InterruptedException,
      IOException {
    Note note = notebook.createNote();
    note.getNoteReplLoader().setInterpreters(factory.getDefaultInterpreterSettingList());

    Paragraph p1 = note.addParagraph();
    p1.setText("p1");
    Paragraph p2 = note.addParagraph();
    p2.setText("p2");
    Paragraph p3 = note.addParagraph();
    p3.setText("p3");
    Paragraph p4 = note.addParagraph();
    p4.setText("p4");

    /* all jobs are ready to run */
    assertEquals(Job.Status.READY, p1.getStatus());
    assertEquals(Job.Status.READY, p2.getStatus());
    assertEquals(Job.Status.READY, p3.getStatus());
    assertEquals(Job.Status.READY, p4.getStatus());

	/* run all */
    note.runAll();

    /* all are pending in the beginning (first one possibly started)*/
    assertTrue(p1.getStatus() == Job.Status.PENDING || p1.getStatus() == Job.Status.RUNNING);
    assertEquals(Job.Status.PENDING, p2.getStatus());
    assertEquals(Job.Status.PENDING, p3.getStatus());
    assertEquals(Job.Status.PENDING, p4.getStatus());

    /* wait till first job is terminated and second starts running */
    while(p1.isTerminated() == false || (p2.getStatus() == Job.Status.PENDING)) Thread.yield();

    assertEquals(Job.Status.FINISHED, p1.getStatus());
    assertEquals(Job.Status.RUNNING, p2.getStatus());
    assertEquals(Job.Status.PENDING, p3.getStatus());
    assertEquals(Job.Status.PENDING, p4.getStatus());

    /* restart interpreter */
    factory.restart(note.getNoteReplLoader().getInterpreterSettings().get(0).id());

    /* pending and running jobs have been aborted */
    assertEquals(Job.Status.FINISHED, p1.getStatus());
    assertEquals(Job.Status.ABORT, p2.getStatus());
    assertEquals(Job.Status.ABORT, p3.getStatus());
    assertEquals(Job.Status.ABORT, p4.getStatus());
  }

  private void delete(File file){
    if(file.isFile()) file.delete();
    else if(file.isDirectory()){
      File [] files = file.listFiles();
      if(files!=null && files.length>0){
        for(File f : files){
          delete(f);
        }
      }
      file.delete();
    }
  }
  
  @Override
  public JobListener getParagraphJobListener(Note note) {
    return new JobListener(){

      @Override
      public void onProgressUpdate(Job job, int progress) {
      }

      @Override
      public void beforeStatusChange(Job job, Status before, Status after) {
      }

      @Override
      public void afterStatusChange(Job job, Status before, Status after) {
      }
    };
  }
}
