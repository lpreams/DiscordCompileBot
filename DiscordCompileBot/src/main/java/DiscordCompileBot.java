import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.login.LoginException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.neovisionaries.ws.client.WebSocketFactory;

import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;

/**
 * A Discord bot that will compile code using glot.io and return the results to Discord
 * @author lpreams
 */
public class DiscordCompileBot extends ListenerAdapter {

	private static final String DISCORD_TOKEN = "<replace with Discord bot token>";
	private static final String GLOT_TOKEN = "<replace with glot.io API token>";

	public static void main(String[] args) throws InterruptedException {
		JDA jda = null;
		try {
			WebSocketFactory wsf = new WebSocketFactory();
			wsf.setSSLSocketFactory(getSocketFactory());
			jda = new JDABuilder(AccountType.BOT).setWebsocketFactory(wsf).setToken(DISCORD_TOKEN).build();
			jda.awaitReady();
		} catch (LoginException e) {
			System.err.println("Trouble logging in to Discord");
			System.err.println(e.getMessage());
			e.printStackTrace();
		} catch (IllegalArgumentException | InterruptedException e) {
			e.printStackTrace();
		}
		jda.addEventListener(new DiscordCompileBot());
	}
	
	private static final SSLSocketFactory getSocketFactory() {

		class NaiveTrustManager implements X509TrustManager {
			public void checkClientTrusted(X509Certificate[] chain, String authType) {
			}

			public void checkServerTrusted(X509Certificate[] chain, String authType) {
			}

			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		}

		SSLSocketFactory sslSocketFactory;
		try {
			TrustManager[] tm = new TrustManager[] { new NaiveTrustManager() };
			SSLContext context = SSLContext.getInstance("TLSv1");
			context.init(new KeyManager[0], tm, new SecureRandom());

			sslSocketFactory = (SSLSocketFactory) context.getSocketFactory();

		} catch (KeyManagementException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}

		return sslSocketFactory;
	}

	private void handleCompile(MessageReceivedEvent event) {
		String message = event.getMessage().getContentRaw();
		String lang = null, file = null;
		try (Scanner scan = new Scanner(message)) {
			scan.next(); // should contain "?compile"
			lang = scan.next();
			if (lang.compareTo("languages") == 0) {
				displayCompileLangs(event);
				return;
			}
			file = scan.next();
			if (lang == null || file == null) {
				event.getChannel().sendMessage(
						event.getAuthor().getAsMention() + " you must specify a language and filename after ?compile")
						.complete();
				return;
			}
		}
		String prog = null, stdin = "";
		try (Scanner scan = new Scanner(message)) {
			while (scan.hasNext()) {
				String line = scan.nextLine();
				if (line.startsWith("```")) {
					StringBuilder sb = new StringBuilder();
					boolean progFound = false;
					while (scan.hasNext()) {
						line = scan.nextLine();
						if (line.startsWith("```")) {
							progFound = true;
							break;
						} else sb.append(line + "\n");
					}
					if (progFound) prog = sb.toString();
					else {
						event.getChannel()
								.sendMessage(event.getAuthor().getAsMention() + " I didn't understand your request")
								.complete();
						return;
					}
					break;
				}
			}

			while (scan.hasNext()) {
				String line = scan.nextLine();
				if (line.startsWith("```")) {
					StringBuilder sb = new StringBuilder();
					boolean stdinFound = false;
					while (scan.hasNext()) {
						line = scan.nextLine();
						if (line.startsWith("```")) {
							stdinFound = true;
							break;
						} else sb.append(line + "\n");
					}
					if (stdinFound) stdin = sb.toString();
					break;
				}
			}
		}
		if (prog == null) {
			event.getChannel().sendMessage(event.getAuthor().getAsMention() + " I didn't understand your request")
					.complete();
			return;
		}
		runProg(event, lang, file, prog, stdin);
	}

	private static final Gson g() {
		return new GsonBuilder().setPrettyPrinting().create();
	}

	private void runProg(MessageReceivedEvent event, String lang, String file, String prog, String stdin) {
		List<HashMap<String, String>> langs;
		try {
			langs = listCompileLangs();
		} catch (Exception e) {
			event.getChannel().sendMessage("Error contacting api (" + e + " " + e.getMessage() + ")").complete();
			return;
		}
		String langURL = null;
		for (HashMap<String, String> map : langs)
			if (map.get("name").compareTo(lang.toLowerCase().trim()) == 0) langURL = map.get("url") + "/latest";
		if (langURL == null) {
			event.getChannel().sendMessage("Unknown language " + lang).complete();
			return;
		}
		Gson g = g();
		String jsonProg = g.toJson(new GlotRun(file, prog, stdin));
		Type type = new TypeToken<HashMap<String, String>>() {
		}.getType();
		HashMap<String, String> response;
		try {
			response = g.fromJson(api(langURL, true, jsonProg), type);
		} catch (Exception e) {
			event.getChannel().sendMessage("Error (" + e + " " + e.getMessage() + ")").complete();
			return;
		}
		String stdout = response.get("stdout");
		String stderr = response.get("stderr");
		String error = response.get("error");

		StringBuilder sb = new StringBuilder();
		sb.append("```\n");
		sb.append(stdout);
		sb.append("\n```\n");
		if (stderr != null && !stderr.isEmpty()) {
			sb.append("\nstderr:\n```\n");
			sb.append(stderr);
			sb.append("\n```\n");
		}
		if (error != null && !error.isEmpty()) {
			sb.append("\nError:\n```\n");
			sb.append(error);
			sb.append("\n```\n");
		}
		event.getChannel().sendMessage(sb.toString()).complete();
	}

	private static class GlotRun {
		@SuppressWarnings("unused")
		public final String stdin;
		public final List<HashMap<String, String>> files;

		public GlotRun(String file, String prog, String stdin) {
			this.stdin = stdin;
			files = new ArrayList<>();
			HashMap<String, String> map = new HashMap<>();
			map.put("name", file);
			map.put("content", prog);
			files.add(map);
		}
	}

	private void displayCompileLangs(MessageReceivedEvent event) {
		List<HashMap<String, String>> langs;
		try {
			langs = listCompileLangs();
		} catch (Exception e) {
			event.getChannel().sendMessage("Error contacting api (" + e + " " + e.getMessage() + ")").complete();
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (HashMap<String, String> lang : langs)
			sb.append(lang.get("name") + System.lineSeparator());
		event.getChannel().sendMessage(sb.toString()).complete();
	}

	private List<HashMap<String, String>> listCompileLangs() throws Exception {
		Gson g = g();
		Type listType = new TypeToken<List<HashMap<String, String>>>() {
		}.getType();
		List<HashMap<String, String>> maps = g.fromJson(api("https://run.glot.io/languages", false, null), listType);
		return maps;
	}

	private static String api(String urlString, boolean doPost, String postString) throws Exception {
		if (doPost) {
			System.out.println("POSTing JSON:\n" + postString);
		}
		URL url = new URL(urlString);
		byte[] postDataBytes = null;
		if (doPost) postDataBytes = postString.toString().getBytes("UTF-8");

		HttpURLConnection conn = (HttpURLConnection) url.openConnection();

		if (doPost) conn.setRequestMethod("POST");
		else conn.setRequestMethod("GET");

		conn.setRequestProperty("Content-Type", "application/json");
		if (doPost) {
			conn.setRequestProperty("Authorization", "Token " + GLOT_TOKEN);
			conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
			conn.setDoOutput(true);
			conn.getOutputStream().write(postDataBytes);
		}
		Reader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));

		StringBuilder json = new StringBuilder();
		for (int c; (c = in.read()) >= 0;)
			json.append((char) c);

		return json.toString();
	}

	public void onMessageReceived(MessageReceivedEvent event) {
		System.out.println(event.getAuthor().getName() + " " + event.getMessage().getContentDisplay());
		if (event.getMessage().getContentRaw().startsWith("?compile ")) {
			if (event.getMessage().getContentRaw().startsWith("?compile languages")) displayCompileLangs(event);
			else handleCompile(event);
			return;
		}
	}
}
