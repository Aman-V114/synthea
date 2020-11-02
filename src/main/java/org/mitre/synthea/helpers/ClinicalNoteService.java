package org.mitre.synthea.helpers;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * This class is designed to interact with the RESTful notes generation service
 * of the GMHD project.
 */
public class ClinicalNoteService {
  public static HttpUrl noteServiceUrl = HttpUrl.parse(Config.get("generate.clinical_note_url",
      "http://127.0.0.1:4567/custom_input_note"));
  public static final String NOTE_TYPE = "ooGENERALoo";
  public static final MediaType JSON
      = MediaType.get("application/json; charset=utf-8");

  /* Tokens returned from the note service that need to be replaced */
  public static final String DATE_TOKEN = "ooDATEoo";
  public static final String NAME_TOKEN = "ooNAMEoo";
  public static final String ID_NUMBER_TOKEN = "oIDoNUMBERo"; // don't know why that this is a
                                                              // single "o" case, but it's what
                                                              // the service gives us.
  public static final String LOCATION_TOKEN = "ooLOCATIONoo";
  public static final String HOSPITAL_WARD_TOKEN = "ooHOSPITALoWARDoo";
  public static final String CONTACT_INFO_TOKEN = "ooCONTACToINFOoo";
  public static final String ADDRESS_COMPONENT_TOKEN = "ooADDRESSoCOMPONENToo";

  public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM d, YYYY");

  private static OkHttpClient client;

  /**
   * The body of the request to be sent to the GMHD service.
   */
  public static class NoteRequestBody {
    public String noteType;
    public int age;
    public String gender;
    public String ethnicity;
    public String doctorName;
    @SerializedName("marital_status")
    public String maritalStatus;
    public List<String> diagnosis;
    public List<String> procedures;
    public List<String> drugs;

    public NoteRequestBody(Person person, long time) {
      this.noteType = NOTE_TYPE;
      this.age = person.ageInYears(time);
      this.gender = (String) person.attributes.get(Person.GENDER);
      this.ethnicity = (String) person.attributes.get(Person.ETHNICITY);
      this.maritalStatus = (String) person.attributes.get(Person.MARITAL_STATUS);
      if (this.maritalStatus == null) {
        this.maritalStatus = "S";
      }
      this.diagnosis = new LinkedList();
      this.procedures = new LinkedList();
      this.drugs = new LinkedList();

      HealthRecord record = person.record;
      if (record.provider != null) {
        this.doctorName = record.provider.name;
      }
      record.encounters.forEach(encounter -> {
        encounter.conditions.forEach(condition -> {
          if (!this.diagnosis.contains(condition.name)) {
            this.diagnosis.add(condition.name);
          }
        });
        encounter.procedures.forEach(procedure -> {
          if(!this.procedures.contains(procedure.name)) {
            this.procedures.add(procedure.name);
          }
        });
        encounter.medications.forEach(medication -> {
          if(!this.drugs.contains(medication.name)) {
            this.drugs.add(medication.name);
          }
        });
      });
    }

    public String toJSON() {
      Gson gson = new Gson();
      return gson.toJson(this);
    }
  }

  /**
   * Generate a note for the the supplied person at the time provided.
   * @param person To generate the note for
   * @param time Used to calculate the person's age for the note
   */
  public static void generateNote(Person person, long time) {
    if (client == null) {
      client = new OkHttpClient.Builder()
          .readTimeout(10, TimeUnit.MINUTES).build();
    }
    NoteRequestBody noteRequestBody = new NoteRequestBody(person, time);
    RequestBody body = RequestBody.create(noteRequestBody.toJSON(), JSON);
    Request request = new Request.Builder()
        .url(noteServiceUrl)
        .post(body)
        .build();
    try {
      Response response = client.newCall(request).execute();
      String templatedNote = response.body().string();
      String populatedNote = replaceNoteTokens(templatedNote, person, time);
      person.record.note = new HealthRecord.Note(populatedNote, time);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static String replaceNoteTokens(String note, Person person, long time) {
    String populatedNote = note;
    if (populatedNote.contains(DATE_TOKEN)) {
      populatedNote = populatedNote.replace(DATE_TOKEN, DATE_FORMAT.format(new Date(time)));
    }
    if (populatedNote.contains(NAME_TOKEN)) {
      String name = person.attributes.get(Person.FIRST_NAME) + " "
          + person.attributes.get(Person.LAST_NAME);
      populatedNote = populatedNote.replace(NAME_TOKEN, name);
    }
    if (populatedNote.contains(ID_NUMBER_TOKEN)) {
      populatedNote = populatedNote.replace(ID_NUMBER_TOKEN,
          (String) person.attributes.get(Person.ID));
    }
    if (populatedNote.contains(LOCATION_TOKEN)) {
      populatedNote = populatedNote.replace(LOCATION_TOKEN,
          (String) person.attributes.get(Person.CITY));
    }
    if (populatedNote.contains(HOSPITAL_WARD_TOKEN)) {
      populatedNote = populatedNote.replace(HOSPITAL_WARD_TOKEN,
          person.record.provider.name);
    }
    if (populatedNote.contains(CONTACT_INFO_TOKEN)) {
      populatedNote = populatedNote.replace(CONTACT_INFO_TOKEN,
          (String) person.attributes.get(Person.TELECOM));
    }
    if (populatedNote.contains(ADDRESS_COMPONENT_TOKEN)) {
      populatedNote = populatedNote.replace(ADDRESS_COMPONENT_TOKEN,
          (String) person.attributes.get(Person.ADDRESS));
    }

    return populatedNote;
  }
}
