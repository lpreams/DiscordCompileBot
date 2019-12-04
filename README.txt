1. In DiscordCompileBot.java, replace `DISCORD_TOKEN` and `GLOT_TOKEN` with your Discord and glot.io API tokens

2. Run DiscordCompileBot.java (`./gradlew run` on *nix)

3. Enter `?compile languages` in Discord to list available programming languages

4. Enter something like the following to compile and run a program:

?compile java HelloWorld.java
```java
public class HelloWorld {
    public static void main(String args[]) {
        System.out.println("Hello, world!");
    }
}
```
