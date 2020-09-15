package be.huffle.mongoose;

public class App
{
	public static void main(String[] args)
	{
		String token = System.getProperty("TOKEN");
		Mongoose mongoose = new Mongoose(token);
		mongoose.run();
	}
}
