package be.huffle.mongoose;

public class App
{
	public static void main(String[] args)
	{
		String token = System.getProperty("TOKEN");
		if (token == null)
		{
			System.out.println("A token is needed in the configurations");
		}
		else
		{
			Mongoose mongoose = new Mongoose(token);
			mongoose.run();
		}
	}
}
