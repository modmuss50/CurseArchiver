package me.modmuss50.ca;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.common.net.UrlEscapers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * This is used to download evey file from curse, it will take a while. It can be re-ran to update with any new files.
 *
 * You run this at your own risk, I doubt curse like it, but it works.
 *
 * If you want to download mod packs textures, or anything else see the doc on `isValid`
 */
public class CurseArchiver {

	static File dataDir = new File("data");
	static File curseMeta = new File(dataDir, "meta.json");
	static File projectsFile = new File(dataDir, "projects");
	static Gson gson = new GsonBuilder().setPrettyPrinting().create();
	static File errorLog = new File(dataDir, "error.log");
	static int done;

	/**
	 * Change this if you want to download mod packs, worlds or texture packs.
	 */
	public static Predicate<CleanedRaw.Project> isValid() {
		return project -> project.type.equals("mod");
	}

	/**
	 * Set this to true to always check the has of the file, and redownload if the hash isnt stored
	 */
	static boolean alwaysHashCheck() {
		return true;
	}

	public static void main(String[] args) throws IOException {
		System.out.println("Downloading curse meta...");
		FileUtils.copyURLToFile(new URL("https://cursemeta.dries007.net/cleaned_raw.json"), curseMeta);
		System.out.println("Reading json");
		CleanedRaw projects = gson.fromJson(FileUtils.readFileToString(curseMeta, StandardCharsets.UTF_8), CleanedRaw.class);
		//Gets a count of the projects that need to be processed
		long todo = projects.projects.entrySet().stream().map(Map.Entry::getValue).filter(isValid()).count();

		//This is the main loop, note the parallelStream, this may make file downloading show up mixed up in the log, change this to just stream if you want to fix that.
		//Doing so may make it slower to download though.
		projects.projects.entrySet().parallelStream().map(Map.Entry::getValue).filter(isValid()).forEach(project -> {
			processProject(project, projects);
			System.out.println("Completed " + project.title + "     Done: " + done + "/" + todo + "  " + (done * 100) / todo + "%");
			done++;
		});
		System.out.println("Done");
	}

	public static void processProject(CleanedRaw.Project project, CleanedRaw rawData) {
		File typeFile = new File(projectsFile, project.type);
		File projectData = new File(typeFile, project.id + "");
		File projectNameFile = new File(projectData, project.title.replaceAll("[^a-zA-Z0-9]", "") + ".txt"); //This file makes it easy to search for projects
		File fileStore = new File(projectData, "files");
		if (!fileStore.exists()) {
			fileStore.mkdirs();
		}
		if(!projectNameFile.exists()){
			try {
				projectNameFile.createNewFile();
			} catch (IOException e) {
				log(e);
			}
		}
		File projectDataFile = new File(projectData, "project.json");

		ProjectData pData;
		if (projectDataFile.exists()) {
			try {
				pData = gson.fromJson(FileUtils.readFileToString(projectDataFile, StandardCharsets.UTF_8), ProjectData.class);
			} catch (IOException e) {
				e.printStackTrace();
				pData = new ProjectData();
				pData.fileDataMap = new HashMap<>();
			}
		} else {
			pData = new ProjectData();
			pData.fileDataMap = new HashMap<>();
		}
		pData.curseProjectData = project;
		final HashMap<Integer, FileData> fileDataMap = pData.fileDataMap;

		//Done parallel to speed it up a bit
		project.files.parallelStream().forEach(fileId -> {
			CleanedRaw.File file = rawData.files.get(fileId + ""); //Done to make it a string so it finds the file
			if (file == null) {
				System.out.printf("Failed to find file " + fileId + " for project " + project.title);
				return;
			}
			File jFile = new File(fileStore, file.id + "-" + file.filename);
			File jFileData = new File(fileStore, file.id + "-" + file.filename + ".json");
			if ((!jFileData.exists() && jFile.exists()) && !alwaysHashCheck()) { //If the file exists and no data json is found, skip it
				return;
			} else if (jFileData.exists() && jFile.exists()) {
				try {
					//Reas the data json and cheks the hash, if the file is good we skip it
					FileData data = gson.fromJson(FileUtils.readFileToString(jFileData, StandardCharsets.UTF_8), FileData.class);
					if (!data.sha256.isEmpty() && hash(jFile, Hashing.sha256()).equals(data.sha256)) {
						return;
					}
				} catch (IOException e) {
					log(e);
				}
			}
			try {
				System.out.println("        Downloading file: " + file.name + " for project " + project.id);
				FileUtils.copyURLToFile(new URL(UrlEscapers.urlFragmentEscaper().escape(file.url)), jFile);

				FileData fileData = new FileData(file, jFile);
				FileUtils.writeStringToFile(jFileData, gson.toJson(fileData), StandardCharsets.UTF_8);
				fileDataMap.put(fileId, fileData);
			} catch (IOException e) {
				log(e);
			}
		});

		pData.fileDataMap = fileDataMap;
		try {
			FileUtils.writeStringToFile(projectDataFile, gson.toJson(pData), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log(e);
		}
	}

	static void log(Exception e) {
		e.printStackTrace();
		try {
			FileUtils.writeStringToFile(errorLog, ExceptionUtils.getStackTrace(e), StandardCharsets.UTF_8, true);
		} catch (IOException e1) {
			//Well fuck
			e1.printStackTrace();
		}
	}

	static String hash(File file, HashFunction hashFunction) {
		try {
			HashCode hash = Files.asByteSource(file).hash(hashFunction);
			StringBuilder builder = new StringBuilder();
			for (Byte hashBytes : hash.asBytes()) {
				builder.append(Integer.toString((hashBytes & 0xFF) + 0x100, 16).substring(1));
			}
			return builder.toString();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return "";
	}

	static class FileData {
		CleanedRaw.File curseFileInfo;
		String sha1;
		String md5;
		String sha256;
		String filename;

		public FileData(CleanedRaw.File curseFileInfo, File file) {
			this.curseFileInfo = curseFileInfo;
			this.sha1 = hash(file, Hashing.sha1());
			this.sha256 = hash(file, Hashing.sha256());
			this.md5 = hash(file, Hashing.md5());
			this.filename = file.getName();
		}
	}

	static class ProjectData {
		HashMap<Integer, FileData> fileDataMap;

		CleanedRaw.Project curseProjectData;
	}

	//https://cursemeta.dries007.net/cleaned_raw.json
	static class CleanedRaw {

		HashMap<String, Project> projects;

		HashMap<String, File> files;

		public class Project {
			public int id;
			public List<String> versions;
			public String desc;
			public boolean featured;
			public int defaultFile;
			public String primaryAuthor;
			public List<Integer> files;
			public List<Integer> categories;
			public List<Attachment> attachments;
			public String site;
			public String title;
			public int rank;
			public String type;
			public String stage;
			public List<String> authors;
			public float popularity;
			public int downloads;
			public int primaryCategory;

			public class Attachment {
				public String desc;
				public String thumbnail;
				public String url;
				public String name;
				@SerializedName("default")
				public boolean defaultAttachment;
			}
		}

		public class File {
			public int id;
			public int date;
			public List<String> versions;
			public List<Dependence> dependencies;
			public boolean available;
			public String name;
			public long fingerprint;
			public String type;
			public int alternativeFile;
			public String filename;
			public int project;
			public String url;
			public boolean alternate;

			public class Dependence {
				String Type;
				int AddOnID;
			}
		}

	}

}
